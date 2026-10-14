# Plano de estudos: concorrência em Java/Spring com Postgres

API de reserva de laboratórios usada para reproduzir, e depois corrigir, um problema de concorrência. Cada fase fica numa branch.

| Fase | Branch | Temas | Status |
|---|---|---|---|
| 1. Reproduzir a condição de corrida | `fase-1/condicao-de-corrida` | Tema 1 (check-then-act, `Read Committed`) e Tema 3 (`ExecutorService`, latch de largada, Postgres real) | concluída |
| 2. Corrigir com lock pessimista | `fase-2/lock-pessimista` | Tema 2 e Tema 3 (`TransactionTemplate`, latches para forçar ordem entre threads) | concluída |
| 2b. Comparar com lock otimista | `fase-2/lock-otimista` | Tema 2 (`@Version`, `OPTIMISTIC_FORCE_INCREMENT`, falso conflito, quando usar cada um) | concluída |
| 3. Garantir no banco com constraint de exclusão | `fase-3/constraint-exclusao` | Restante do Tema 1 (`EXCLUDE USING gist`, `btree_gist`, `tstzrange`, constraint parcial, SQLState, flush, tradução para 409) | concluída |

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

## Fase 3: constraint de exclusão no banco

Os dois locks da fase 2 só protegem quem os usa. Um script, uma importação em lote ou um novo método que insira reservas sem chamar `findParaReservaById` traria a corrida de volta. A fase 3 coloca a regra "não sobrepor horário" no próprio banco, onde ela vale para qualquer `INSERT`, venha de onde vier.

### A constraint

```sql
ALTER TABLE reserva ADD CONSTRAINT reserva_sem_sobreposicao
    EXCLUDE USING gist (                          -- proíba pares de linhas, usando um índice GiST
        laboratorio_id WITH =,                    -- que tenham o mesmo laboratório
        tstzrange(inicio, fim, '[)') WITH &&      -- e cujo intervalo [inicio, fim) se sobreponha
    )
    WHERE (status = 'AGENDADA');                  -- considerando só reservas ativas
```

**`EXCLUDE`: um `UNIQUE` com operador à escolha.** O `UNIQUE` só compara com igualdade: "não podem existir duas linhas com o mesmo CPF". Reservas das 14h às 16h e das 15h às 17h não são iguais, mas conflitam. O `EXCLUDE` permite escolher o operador de cada coluna. Um par de linhas é proibido só se **todas** as comparações derem verdadeiro ao mesmo tempo: mesmo laboratório **e** horários sobrepostos.

**`tstzrange(inicio, fim, '[)')`: duas colunas viram um intervalo.** `range` é o tipo do Postgres para intervalos, e `tstz` é o `TIMESTAMPTZ` das colunas. O intervalo é calculado a partir de `inicio` e `fim`. Nenhuma coluna nova é criada.

**`'[)'`: quais pontas pertencem ao intervalo.** `[` inclui a ponta, e `)` exclui. Uma reserva das 14h às 16h vale de 14:00 até um instante antes das 16:00. Assim, 14h-16h e 16h-18h não conflitam. Com `'[]'`, as duas teriam o instante 16:00 em comum, e horários colados seriam recusados.

| Reserva A | Reserva B | `'[)'` | `'[]'` |
|---|---|---|---|
| 14h-16h | 16h-18h | sem conflito | conflito (16:00 nas duas) |
| 14h-16h | 15h-17h | conflito | conflito |
| 14h-16h | 17h-19h | sem conflito | sem conflito |

É a mesma regra do `existeConflito` (`r.inicio < novoFim AND r.fim > novoInicio`, com comparações estritas). Aplicação e banco concordam sobre o que é conflito.

**`&&`: "se sobrepõe".** Para ranges, `&&` é verdadeiro quando os dois intervalos têm pelo menos um ponto em comum. Não tem relação com o `&&` do Java.

**`USING gist`: o índice que sabe responder "se sobrepõe".** Toda constraint desse tipo usa um índice para não comparar cada `INSERT` com a tabela inteira. O índice padrão, B-tree, ordena valores e responde igual, menor e maior, mas não sobreposição. O GiST (*Generalized Search Tree*) agrupa valores com "forma", como intervalos e áreas, em caixas que se contêm. Para achar o que cruza 14h-16h, ele descarta caixas inteiras sem olhar dentro delas.

**`btree_gist`: igualdade dentro do GiST.** O GiST sabe comparar ranges com `&&`, mas não sabe comparar um `bigint` com `=`. A extensão `btree_gist` ensina isso a ele. Sem ela, o índice não consegue combinar `laboratorio_id WITH =` e o range `WITH &&`, e o `ALTER TABLE` falha.

**`WHERE (status = 'AGENDADA')`: constraint parcial.** Reservas canceladas ficam fora da regra. Sem esse filtro, cancelar uma reserva não liberaria o horário.

Num `INSERT` que viola a regra, o Postgres recusa com SQLState `23P01` (`exclusion_violation`).

### A migration V3 e os dados que já existiam

O banco de desenvolvimento tinha 90 pares de reservas agendadas sobrepostas, deixados pelas execuções do teste da fase 1. O Postgres valida as linhas existentes ao criar a constraint, então a V3 falharia. As decisões seguiram o que se faria em produção:

- **Limpar na migration, não zerar o banco.** Em produção, não existe recriar o banco. A V3 corrige os dados antes de criar a constraint.
- **Cancelar, não apagar.** O cancelamento é o soft delete do projeto e preserva o histórico. Reservas canceladas ficam fora da constraint.
- **Quem reservou primeiro fica.** Entre reservas que conflitam, sobrevive a de menor `criado_em`, com desempate pelo menor `id`.
- **Cancelamento atribuído a um usuário Sistema.** A V1 exige `cancelado_por`. Usar o dono da reserva registraria um cancelamento que ele não fez. A V3 cria o usuário `sistema` com CPF `00000000000`. CPFs com todos os dígitos iguais são inválidos pela regra do dígito verificador, então esse valor nunca coincide com uma pessoa real. As reservas canceladas pela migration ficam identificáveis por esse autor e pelo mesmo `cancelado_em`.

A V3 executa, nesta ordem:

1. `CREATE EXTENSION IF NOT EXISTS btree_gist`;
2. cria o usuário Sistema;
3. um bloco PL/pgSQL (`DO $$ ... $$`) que percorre as reservas agendadas da mais antiga para a mais nova e cancela uma reserva só se ela conflitar com uma **anterior que ainda esteja agendada**;
4. cria a constraint.

**Por que um loop, e não um `UPDATE` só.** Um `UPDATE ... WHERE EXISTS` avalia todas as linhas vendo o banco como estava antes do comando. Dentro do loop, cada volta enxerga os cancelamentos das anteriores. Isso importa em cadeias:

| Volta | Reserva | Existe anterior agendada que conflita? | Resultado |
|---|---|---|---|
| 1 | A (14h-16h) | não há anterior | fica |
| 2 | B (15h-17h) | A, que sobrepõe 15h-16h | cancelada |
| 3 | C (16h-18h) | A não sobrepõe; B sobreporia, mas já foi cancelada | fica |

Um `UPDATE` único ainda veria B como agendada e cancelaria C sem necessidade.

**Atomicidade.** DDL no Postgres é transacional, e o Flyway roda a V3 numa transação. Se qualquer passo falhar, os quatro são desfeitos juntos. O banco nunca fica com a limpeza feita e sem a constraint.

**Cuidados para produção:**
- Antes de aplicar, rodar o mesmo critério como `SELECT`, para listar as reservas que serão canceladas e avisar os donos.
- `ADD CONSTRAINT` trava a tabela `reserva` enquanto monta o índice. Numa tabela grande, a migration roda numa janela de manutenção.
- O Flyway gratuito não desfaz migrations. Remover a constraint exige uma V4, e as reservas canceladas não voltam sozinhas.

### Do banco até a resposta HTTP

Quando a constraint recusa um `INSERT`, a exceção sobe em camadas:

```
DataIntegrityViolationException              (Spring)     <- o que o handler recebe
  └ ConstraintViolationException             (Hibernate)  <- getConstraintName()
      └ PSQLException, uma SQLException      (driver)     <- getSQLState() = "23P01"
```

O `GlobalExceptionHandler` trata `DataIntegrityViolationException` e decide pela causa:

- **SQLState `23P01`:** responde **409 "Reserva conflitante"**, a mesma resposta da `ReservaConflitoException`. Para o cliente, tanto faz se quem detectou foi a aplicação ou o banco.
- **Qualquer outra violação:** o handler relança a exceção e o Spring responde 500 sem expor a mensagem. Assim, um CPF repetido ou uma chave estrangeira inválida não vira "horário ocupado" por engano.

`ex.getMostSpecificCause()` desce até a `SQLException`. O SQLState é verificado nela, e não na mensagem, porque o código é estável e a mensagem muda com versão e idioma do banco.

**Flush.** O id usa `IDENTITY`, então o Hibernate executa o `INSERT` já no `save`, para obter o id. A violação estoura na linha do `save`, dentro do método, e não no commit.

**Quando o conflito chega ao banco.** No caminho pessimista, o lock impede que duas reservas sobrepostas cheguem ao `INSERT`. A constraint só dispara quando alguém grava sem o lock. Se a outra transação ainda não commitou, o `INSERT` **espera** por ela, como faria com um `UNIQUE`: só depois do commit o Postgres sabe se existe conflito.

### CPF duplicado

Ao montar o handler, apareceu um problema que já existia: cadastrar ou atualizar um usuário com CPF repetido violava o `UNIQUE` da V1 (`23505`) e respondia 500. A correção usa o mesmo padrão de duas camadas da reserva:

1. **No service, para a mensagem amigável.** `existsByCpf` no cadastro e `existsByCpfAndIdNot` na atualização (a busca ignora o próprio usuário, para ele poder salvar sem mudar o CPF). Se o CPF já pertence a outra pessoa, o service lança `CpfJaExistenteException` e a API responde 409.
2. **No banco, como garantia final.** A verificação do service é um check-then-act: dois cadastros simultâneos passam juntos por ela. O `UNIQUE` recusa o segundo com `23505`, e o handler responde o mesmo 409.

O handler reconhece o caso do CPF pelo **nome** da constraint, `usuario_cpf_key`, e não só pelo código. `23505` aparece em qualquer `UNIQUE`, e `nome_laboratorio` também é único. O nome vem do Hibernate (`ConstraintViolationException.getConstraintName()`). Atenção ao import: `jakarta.validation.ConstraintViolationException` é outra classe, da validação dos DTOs.

`usuario_cpf_key` é o nome que o Postgres gerou, porque a V1 não deu nome à constraint (regra `<tabela>_<coluna>_key`). Para consultar os nomes: `\d usuario` no `psql`, ou `SELECT conname FROM pg_constraint WHERE conrelid = 'usuario'::regclass`. Nas próximas migrations, toda constraint recebe nome explícito, como `reserva_sem_sobreposicao`, para o código não depender de um nome gerado.

### Testes da fase 3

`ReservaConstraintTest` insere reservas direto pelo repository, sem passar pelo lock, para simular o código que esquece o lock:

| Teste | O que prova |
|---|---|
| `bancoRecusaReservaSobrepostaMesmoSemLock` | 14h-16h e depois 15h-17h: o banco recusa a segunda com `23P01` |
| `bancoAceitaHorarioColadoEHorarioDeReservaCancelada` | 16h-18h ao lado de 14h-16h é aceita (`'[)'`), e uma cancelada no mesmo horário também (`WHERE` parcial) |
| `conflitoDetectadoPeloBancoViraHttp409` | A grava sem lock e segura o commit; B passa pelo lock e pelo `existeConflito`, o `INSERT` espera A e, depois do commit de A, a API responde 409 "Reserva conflitante" |

Com a constraint, o teste de 10 threads do otimista muda de comportamento:

```
Resultado[sucessos=1, conflitos=0, falhasDeVersao=0, recusasDoBanco=9, reservasNoBanco=1]
```

As 9 perdedoras inseriram antes do commit do vencedor, esperaram por ele e foram recusadas pela constraint, antes de chegar à conferência de versão. Quem detecta o conflito passou a ser o banco. O teste conta as três formas de perder (conflito de horário, falha de versão e recusa do banco) e exige 1 sucesso e 1 reserva no banco.

```
docker compose up -d
./mvnw test
```

## Conclusão

| Camada | Protege contra | Não protege contra |
|---|---|---|
| `existeConflito` sozinho (fase 1) | reservas feitas uma de cada vez | requisições simultâneas |
| Lock pessimista (fase 2) | requisições simultâneas que passam pelo lock | código que grava sem o lock |
| Lock otimista (fase 2b) | requisições simultâneas, sem espera | falso conflito; código que grava sem o lock |
| Constraint de exclusão (fase 3) | qualquer `INSERT`, de qualquer origem | nada nessa regra; a mensagem de erro ao cliente depende do handler |

As camadas se somam. O lock organiza a fila e devolve a resposta de negócio sem desperdiçar trabalho. A constraint é a garantia final, que vale mesmo quando alguém esquece o lock.

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
