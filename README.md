# Plano de estudos: concorrência em Java/Spring com Postgres

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
