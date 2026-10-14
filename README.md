# Plano de estudos: concorrência em Java/Spring com Postgres

API de reserva de laboratórios usada para reproduzir, e depois corrigir, um problema de concorrência. Cada fase fica numa branch.

| Fase | Branch | Temas | Status |
|---|---|---|---|
| 1. Reproduzir a condição de corrida | `fase-1/condicao-de-corrida` | Tema 1 (check-then-act, `Read Committed`) e Tema 3 (`ExecutorService`, latch de largada, Postgres real) | concluída |
| 2. Corrigir com lock pessimista | `fase-2/lock-pessimista` | Tema 2 e Tema 3 (`TransactionTemplate`, latches para forçar ordem entre threads) | próxima |
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
