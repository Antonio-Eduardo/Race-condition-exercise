# Plano de estudos: concorrência em Java/Spring com Postgres

API de reserva de laboratórios usada para reproduzir, e depois corrigir, um problema de concorrência. Cada fase fica numa branch.

| Fase | Branch | Temas | Status |
|---|---|---|---|
| 1. Reproduzir a condição de corrida | `fase-1/condicao-de-corrida` | Tema 1 (check-then-act, `Read Committed`) e Tema 3 (`ExecutorService`, latch de largada, Postgres real) | concluída |
| 2. Corrigir com lock pessimista | `fase-2/lock-pessimista` | Tema 2 e Tema 3 (`TransactionTemplate`, latches para forçar ordem entre threads) | concluída |
| 2b. Comparar com lock otimista | `fase-2/lock-otimista` | Tema 2 (`@Version`, `OPTIMISTIC_FORCE_INCREMENT`, falso conflito, quando usar cada um) | concluída |
| 3. Garantir no banco com constraint de exclusão | `fase-3/constraint-exclusao` | Restante do Tema 1 (`EXCLUDE USING gist`, `btree_gist`, `tstzrange`, constraint parcial, SQLState, flush, tradução para 409) | planejada |

O Tema 3 não é uma fase própria: as técnicas de teste entram em cada fase, conforme o teste daquela fase precisa delas. O mesmo `ReservaConcorrenciaTest` é usado nas três fases, para comparar as soluções.

## Fase 1: condição de corrida (check-then-act)

Ao criar uma reserva, o `ReservaService.criarReserva` faz dois passos separados:

1. **Check:** `ReservaRepository.existeConflito` pergunta se já existe reserva `AGENDADA` no mesmo laboratório com horário sobreposto (`r.inicio < novoFim AND r.fim > novoInicio`).
2. **Act:** se não existe, salva a nova reserva.

Com uma requisição por vez, isso funciona. Com requisições simultâneas, não funciona, porque entre o check e o act outra thread pode fazer o mesmo check e receber a mesma resposta.

### Linha do tempo do conflito

Duas pessoas pedem o LAB-01 das 14h às 16h quase no mesmo instante. Cada requisição roda numa thread e numa conexão próprias:

| Tempo | Thread da Ana | Thread do Bruno | Banco |
|---|---|---|---|
| t1 | valida duração | | vazio |
| t2 | busca laboratório | valida duração | vazio |
| t3 | **`existeConflito` → false** | busca laboratório | vazio |
| t4 | busca usuário | **`existeConflito` → false** | vazio |
| t5 | monta a entidade | busca usuário | vazio |
| t6 | **`save`** | monta a entidade | 1 reserva (Ana) |
| t7 | devolve 201 | **`save`** | **2 reservas no mesmo horário** |
| t8 | | devolve 201 | conflito: duas reservas no mesmo horário |

No t4, o Bruno pergunta "está livre?" e a resposta é verdadeira naquele instante, porque a Ana ainda não salvou. Ele decide salvar com uma informação que fica desatualizada logo depois.

Nada impede o segundo `save`:
- **No Java:** as threads não esperam umas pelas outras.
- **No banco:** cada `INSERT`, sozinho, respeita todas as constraints. Não existe uma constraint de "não sobrepor horário".
- **`@Transactional` também não resolveria:** no `Read Committed`, o padrão do Postgres, uma transação não enxerga o `INSERT` que a outra ainda não confirmou.

### Prova automatizada

`ReservaConcorrenciaTest` dispara 10 threads com o mesmo pedido. Todas esperam num `CountDownLatch` e começam juntas. O teste exige 1 sucesso e falha de propósito:

```
sucessos=10 conflitos=0 reservasNoBanco=10
expected: 1
 but was: 10
```

O resultado varia entre execuções, mas qualquer valor acima de 1 confirma a corrida. Na fase 2, o mesmo teste, sem alterações, deve passar.

### Como rodar

```
docker compose up -d
./mvnw test -Dtest=ReservaConcorrenciaTest
```

O teste usa o mesmo banco do desenvolvimento e cria um laboratório e um usuário novos a cada execução. Para zerar o banco: `docker compose down -v`.

## Fase 2: lock pessimista

A corrida da fase 1 existe porque o check e o act não são atômicos: duas transações fazem o check ao mesmo tempo. A correção é obrigar as transações que reservam o mesmo laboratório a entrarem numa fila, uma de cada vez.

### O que mudou

- `LaboratorioRepository.findParaReservaById` busca o laboratório com `@Lock(LockModeType.PESSIMISTIC_WRITE)`. O Postgres recebe um `SELECT ... FOR NO KEY UPDATE` e trava a linha do laboratório.
- `ReservaService.criarReserva` ganhou `@Transactional` e passou a buscar o laboratório por esse método.

A linha travada é a do laboratório, não a das reservas. Não dá para travar a reserva que ainda não existe, mas dá para travar o recurso que todas as reservas disputam.

### Linha do tempo com o lock

O mesmo cenário da fase 1:

| Tempo | Thread da Ana | Thread do Bruno | Banco |
|---|---|---|---|
| t1 | valida duração | valida duração | vazio |
| t2 | **trava o LAB-01** | tenta travar o LAB-01: **espera** | vazio |
| t3 | `existeConflito` → false | esperando | vazio |
| t4 | `save` | esperando | 1 reserva (Ana), não commitada |
| t5 | **commit, solta o lock** | esperando | 1 reserva (Ana) |
| t6 | devolve 201 | **trava o LAB-01** | 1 reserva (Ana) |
| t7 | | **`existeConflito` → true** | 1 reserva (Ana) |
| t8 | | devolve 409 | 1 reserva (Ana) |

No t7, o Bruno enxerga a reserva da Ana. No `Read Committed`, cada comando vê o que já foi commitado quando ele começa. O `existeConflito` do Bruno só roda depois que ele recebe o lock, ou seja, depois do commit da Ana.

### Por que o `@Transactional` é obrigatório

O lock dura até o fim da transação. Sem `@Transactional`, cada chamada ao repository roda numa transação própria. O lock seria solto logo depois do `SELECT`, antes do `existeConflito`, e a corrida voltaria.

### Timeout do lock

Sem limite, quem espera pelo lock espera para sempre. Se uma transação travar o laboratório e demorar, todas as requisições daquele laboratório ficam penduradas, ocupando threads e conexões.

- `findParaReservaById` tem `@QueryHints` com `jakarta.persistence.lock.timeout = 2000`. O Hibernate executa `set local lock_timeout = 2000` antes do `SELECT ... FOR NO KEY UPDATE`. O `set local` vale só até o fim da transação.
- Depois de 2s, o Postgres cancela a espera com o SQLState `55P03` (`lock_not_available`). O Spring entrega `CannotAcquireLockException`.
- O `GlobalExceptionHandler` responde **503** com mensagem fixa. Não é 409, porque não houve conflito de horário: o laboratório só estava ocupado e o cliente pode tentar de novo. Não é 408, porque quem esperou foi o servidor, não o cliente.
- A mensagem é fixa porque `ex.getMessage()` dessa exceção traz o SQL executado, com nomes de tabela e colunas. As exceções próprias do projeto podem usar `ex.getMessage()`, porque o texto delas é escrito no service.

### `FOR NO KEY UPDATE` e chave estrangeira

O Postgres tem quatro intensidades de lock de linha: `FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE` e `FOR KEY SHARE`. O Hibernate usa `FOR NO KEY UPDATE` para `PESSIMISTIC_WRITE`, porque o JPA nunca altera a chave primária.

Todo `INSERT` em `reserva` trava o laboratório referenciado com `FOR KEY SHARE`, para garantir que ele continue existindo. `FOR NO KEY UPDATE` não bloqueia esse lock. Por isso o lock só protege quem o pede: um código que inserisse reservas sem chamar `findParaReservaById` passaria direto, e a corrida voltaria. Por esse motivo a fase 3 coloca uma constraint de exclusão no banco, que vale para qualquer `INSERT`.

### Deadlock

Hoje cada transação trava um único laboratório, e deadlock não acontece. Ele surge quando duas transações travam mais de um recurso em ordens opostas:

1. T1 trava o laboratório 1. T2 trava o laboratório 2.
2. T1 pede o laboratório 2 e espera T2. T2 pede o laboratório 1 e espera T1.
3. Nenhuma pode continuar.

O Postgres verifica esse ciclo depois de `deadlock_timeout` (1s por padrão) e aborta uma das transações com SQLState `40P01` (`deadlock_detected`). A outra continua normalmente.

Prevenção: se um fluxo precisar travar mais de um laboratório, ele deve travar sempre na mesma ordem, por exemplo por id crescente. Assim não existe ciclo.

## Fase 2b: lock otimista, para comparar

Branch `fase-2/lock-otimista`. O lock pessimista continua em `POST /reservas`. O otimista foi criado ao lado, em `POST /reservas/otimista`, para rodar os dois com os mesmos testes.

### A ideia

- **Pessimista:** assume que o conflito vai acontecer. Trava antes, e quem chega depois espera.
- **Otimista:** assume que o conflito é raro. Ninguém trava nem espera. O conflito é detectado na hora do commit, e quem perdeu sofre rollback.

### O que mudou

- Migration `V2__adiciona_version_laboratorio.sql`: coluna `version BIGINT NOT NULL DEFAULT 0` em `laboratorio`.
- `Laboratorio` ganhou `@Version private Long version`, só com getter. Quem preenche e incrementa é o Hibernate. `Long`, e não `long`, porque o Spring Data usa `version == null` para saber se a entidade é nova.
- `LaboratorioRepository.findParaReservaOtimistaById` usa `@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)`.
- `ReservaService.criarReservaOtimista` é igual ao `criarReserva`, mas busca o laboratório por esse método. As regras de duração ficaram no método privado `verificarDuracao`, usado pelos dois.
- `GlobalExceptionHandler` trata `ObjectOptimisticLockingFailureException` com resposta 409 e mensagem fixa. É 409 porque o recurso foi alterado por outra transação enquanto esta trabalhava, que é o significado usual de conflito. A mensagem é fixa pelo mesmo motivo do timeout: a da exceção expõe detalhes internos da entidade.

### Por que `OPTIMISTIC_FORCE_INCREMENT`

`@Version` sozinho só confere a versão quando o laboratório é alterado. Criar uma reserva não altera o laboratório, então nada seria conferido. `OPTIMISTIC_FORCE_INCREMENT` faz o Hibernate executar no commit `update laboratorio set version = versão + 1 where id = ? and version = <versão lida>`. Se nenhuma linha for alterada, outra transação já subiu a versão, e o Hibernate lança a exceção.

Efeito colateral bom: `atualizarLaboratorio` também passa a conferir a versão. Duas edições simultâneas do mesmo laboratório não se sobrescrevem mais em silêncio (lost update).

### Linha do tempo com o otimista

| Tempo | Thread da Ana | Thread do Bruno | Banco |
|---|---|---|---|
| t1 | lê LAB-01, `version = 3` | lê LAB-01, `version = 3` | vazio |
| t2 | `existeConflito` → false | `existeConflito` → false | vazio |
| t3 | `save` | `save` | duas reservas, nenhuma commitada |
| t4 | **commit: `version` 3 → 4** | | 1 reserva (Ana) |
| t5 | devolve 201 | **commit: `where version = 3` altera 0 linhas** | 1 reserva (Ana) |
| t6 | | **rollback, desfaz o `save`** e devolve 409 | 1 reserva (Ana) |

A corrida da fase 1 acontece de verdade nos passos t2 e t3. O otimista não impede a corrida: ele anula quem perdeu no fim.

### Falso conflito

A versão é do laboratório inteiro, não do horário. Se a Ana reserva das 8h às 10h e o Bruno das 14h às 16h no mesmo instante, os horários não se sobrepõem e as duas reservas deveriam valer:

- **Pessimista:** o Bruno espera alguns milissegundos, não encontra conflito e grava. As duas reservas são criadas.
- **Otimista:** os dois leram a mesma versão. Quem commitar depois falha, mesmo sem conflito de horário.

Por isso, na prática, o otimista vem acompanhado de retry. Na nova tentativa, o Bruno lê a versão nova, não encontra conflito e grava. O retry precisa ficar fora do método `@Transactional`, abrindo uma transação nova por tentativa, porque a transação que falhou já está perdida.

### Lado a lado

| | Pessimista | Otimista |
|---|---|---|
| Quem chega depois | espera | não espera |
| Quando o conflito aparece | antes, ao travar | depois, no commit |
| Trabalho de quem perde | nenhum, perdeu antes de inserir | descartado no rollback |
| O erro de quem perde quer dizer | "horário ocupado" (409, título "Reserva conflitante") | "houve concorrência" (409, título "Laboratório ocupado"), sem dizer se o horário estava livre |
| Horários diferentes ao mesmo tempo | os dois gravam | um falha (falso conflito) |
| Precisa de retry | não | na prática, sim |
| Riscos próprios | espera, timeout, deadlock | falso conflito, trabalho desperdiçado |

## Como escolher entre pessimista e otimista

A pergunta central é: **com que frequência duas transações disputam o mesmo dado ao mesmo tempo?**

### Use pessimista quando

- **A disputa é frequente.** Muita gente reservando o mesmo laboratório no início do semestre. Com o otimista, quase todos perderiam no commit e tentariam de novo, e o retry viraria uma fila desorganizada. O pessimista organiza essa fila no banco.
- **Refazer o trabalho é caro.** Se a transação chama outro serviço, envia e-mail ou faz muito processamento, descobrir o conflito só no fim desperdiça tudo isso.
- **O erro precisa ser a resposta de negócio.** Com o pessimista, quem perde recebe "horário ocupado", que é verdade e é definitivo.
- **A transação é curta.** O lock fica preso enquanto a transação durar. Transação curta significa espera curta.

### Use otimista quando

- **A disputa é rara.** Editar o cadastro de um laboratório, por exemplo: quase nunca duas pessoas editam o mesmo ao mesmo tempo. Travar sempre seria pagar por um problema que quase nunca acontece.
- **Ninguém pode ficar esperando.** Leituras e escritas não bloqueiam ninguém. Não existe timeout de lock nem deadlock por ordem de lock.
- **O intervalo entre ler e gravar atravessa requisições.** O usuário abre um formulário, pensa por cinco minutos e salva. Não dá para manter um lock do banco aberto entre duas requisições HTTP. A solução é o cliente devolver a versão que leu, e o otimista confere no `update`. Só o otimista resolve esse caso.

### Sinais de que a escolha está errada

- **Pessimista com muito `CannotAcquireLockException`:** as transações estão longas demais ou o lock está grosso demais.
- **Otimista com muito `ObjectOptimisticLockingFailureException`:** a disputa é maior do que parecia. O pessimista provavelmente serve melhor.
- **Otimista com falso conflito frequente:** a versão está num nível grosso demais (aqui, o laboratório inteiro, em vez do horário).

### Neste projeto

- **Criar reserva:** pessimista. O recurso disputado é o horário de um laboratório, a disputa é alta em horários concorridos, e o falso conflito do otimista recusaria reservas válidas.
- **Editar laboratório:** otimista, com o `@Version` que já existe. A disputa é rara, e ele impede que uma edição sobrescreva a outra sem aviso.
- **Nos dois casos, a fase 3:** nenhum dos locks protege um código que esqueça de usá-lo. A constraint de exclusão no banco é a garantia final.

### Testes da fase 2

| Teste | O que prova |
|---|---|
| `ReservaConcorrenciaTest.apenasUmaReservaDeveSerCriadaParaOMesmoHorario` | 10 threads no mesmo horário com pessimista: 1 sucesso e 9 conflitos de horário |
| `ReservaConcorrenciaTest.lockOtimistaTambemDeixaApenasUmaReserva` | as mesmas 10 threads com otimista: 1 sucesso, e as outras 9 se dividem entre conflito de horário e falha de versão |
| `ReservaLockTest.segundaReservaEsperaOLockEDepoisVeOConflito` | B fica esperando o lock, confirmado por `pg_stat_activity`, e depois vê o conflito |
| `ReservaLockTest.segundaReservaDesisteDepoisDoTimeoutDoLock` | B desiste depois de cerca de 2s, com SQLState `55P03` |
| `ReservaLockTest.timeoutDoLockViraRespostaHttp503SemExporOSql` | o timeout vira 503 pela API, sem SQL na mensagem |
| `ReservaLockTest.pessimistaAceitaHorariosDiferentesNoMesmoLaboratorio` | horários diferentes ao mesmo tempo: os dois gravam |
| `ReservaLockTest.otimistaRecusaHorariosDiferentesNoMesmoLaboratorio` | horários diferentes ao mesmo tempo: um falha no commit (falso conflito) |
| `ReservaLockTest.travarLaboratoriosEmOrdensOpostasGeraDeadlock` | ordem de lock oposta gera `40P01` em exatamente uma das transações |

Uma execução do teste de 10 threads com otimista:

```
Resultado[sucessos=1, conflitos=4, falhasDeVersao=5, reservasNoBanco=1]
```

A divisão entre conflitos e falhas de versão muda a cada execução. Quem chegou depois do commit do vencedor recebe conflito de horário. Quem leu a mesma versão que ele perde no commit.

```
docker compose up -d
./mvnw test -Dtest='Reserva*Test'
```

## Tema 1: Duas reservas no mesmo instante

- **Race condition do tipo check-then-act:** por que "consultar e depois gravar" falha com duas requisições ao mesmo tempo.
- **Transações e níveis de isolamento no Postgres:** `Read Committed`, o padrão, e o que uma transação enxerga do que outra ainda não commitou.
- **Constraints como garantia final:** `UNIQUE` e, principalmente, `EXCLUDE USING gist`.
- **Extensão `btree_gist`:** por que ela é necessária para combinar `=` com `&&`.
- **Tipos range no Postgres:** `tstzrange`, intervalo semiaberto `[inicio, fim)` e o operador de sobreposição `&&`.
- **Constraint parcial:** o `WHERE` dentro da constraint (só reservas ativas contam).
- **SQLState de erros:**
  - `23P01` (`exclusion_violation`)
  - `23505` (`unique_violation`)
  - `40P01` (`deadlock_detected`)
- **Flush no JPA/Hibernate:** quando o `INSERT` é realmente executado e por que isso importa para capturar o erro.
- **Tradução de exceção:** de `DataIntegrityViolationException` para um erro de negócio com resposta `409`.

## Tema 2: Lock pessimista

- **Lost update:** duas transações leem o mesmo dado e uma sobrescreve a outra.
- **`SELECT ... FOR UPDATE`:** o que ele trava, até quando e o que acontece com quem chega depois.
- **`@Lock(LockModeType.PESSIMISTIC_WRITE)`** no Spring Data JPA.
- **`@Transactional` e o tempo de vida do lock:** por que sem transação o lock não serve.
- **Granularidade do lock:** travar só o necessário e o risco de travar tabelas relacionadas.
- **Deadlock:** como surge e como o Postgres resolve.
- **Lock otimista (`@Version`)** para comparar: quando usar cada um.

## Tema 3: Testar concorrência (vale para os dois)

- **`ExecutorService` e `Future`.**
- **`CountDownLatch`:** para largada simultânea e para forçar uma ordem determinística entre threads.
- **`TransactionTemplate`:** para segurar uma transação aberta dentro do teste.
- **Testes de integração com Postgres real:** Testcontainers ou docker compose, já que o H2 não tem `EXCLUDE`.
