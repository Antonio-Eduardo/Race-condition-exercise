package com.exercise.lockpessimist.reserva;

import com.exercise.lockpessimist.laboratorio.Laboratorio;
import com.exercise.lockpessimist.laboratorio.LaboratorioRepository;
import com.exercise.lockpessimist.usuario.Usuario;
import com.exercise.lockpessimist.usuario.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Sem @Transactional: cada thread abre a própria transação pelo TransactionTemplate
@SpringBootTest
@AutoConfigureMockMvc
class ReservaLockTest {

    @Autowired
    private ReservaService reservaService;
    @Autowired
    private LaboratorioRepository laboratorioRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mockMvc;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void encerrarExecutor() {
        executor.shutdownNow();
    }

    @Test
    void segundaReservaEsperaOLockEDepoisVeOConflito() throws Exception {
        Laboratorio laboratorio = novoLaboratorio();
        Usuario usuario = novoUsuario();
        ReservaDtoRequest request = new ReservaDtoRequest(
                Instant.parse("2030-01-10T14:00:00Z"),
                Instant.parse("2030-01-10T16:00:00Z"),
                laboratorio.getId(),
                usuario.getId());

        CountDownLatch aTravou = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);

        // A cria a reserva dentro de uma transação externa e não faz commit até o teste mandar
        Future<?> a = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            reservaService.criarReserva(request);
            aTravou.countDown();
            aguardar(liberarA);
        }));
        assertThat(aTravou.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> b = executor.submit(() -> reservaService.criarReserva(request));

        // Prova pelo próprio Postgres que B está parada esperando um lock, sem depender de sleep
        aguardarSessaoEsperandoLock();
        assertThat(b.isDone()).isFalse();

        liberarA.countDown();
        a.get(10, TimeUnit.SECONDS);

        // B só consulta conflitos depois de obter o lock, e aí já enxerga o commit de A
        Throwable erroB = catchThrowable(() -> b.get(10, TimeUnit.SECONDS));
        assertThat(erroB).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ReservaConflitoException.class);
    }

    @Test
    void segundaReservaDesisteDepoisDoTimeoutDoLock() throws Exception {
        Laboratorio laboratorio = novoLaboratorio();
        Usuario usuario = novoUsuario();
        ReservaDtoRequest request = new ReservaDtoRequest(
                Instant.parse("2030-01-10T14:00:00Z"),
                Instant.parse("2030-01-10T16:00:00Z"),
                laboratorio.getId(),
                usuario.getId());

        CountDownLatch aTravou = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);

        // A segura o lock por mais tempo que o timeout configurado em findParaReservaById
        Future<?> a = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            laboratorioRepository.findParaReservaById(laboratorio.getId()).orElseThrow();
            aTravou.countDown();
            aguardar(liberarA);
        }));
        assertThat(aTravou.await(10, TimeUnit.SECONDS)).isTrue();

        long inicio = System.nanoTime();
        Throwable erroB = catchThrowable(() -> reservaService.criarReserva(request));
        long esperouMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);

        liberarA.countDown();
        a.get(10, TimeUnit.SECONDS);

        System.out.printf("timeout: %s apos %dms%n", erroB.getClass().getName(), esperouMs);
        assertThat(erroB).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(sqlState(erroB)).isEqualTo("55P03");
        assertThat(esperouMs).isBetween(1500L, 5000L);
    }

    @Test
    void timeoutDoLockViraRespostaHttp503SemExporOSql() throws Exception {
        Laboratorio laboratorio = novoLaboratorio();
        Usuario usuario = novoUsuario();
        String corpo = """
                {"inicio": "2030-01-10T14:00:00Z", "fim": "2030-01-10T16:00:00Z", "idLaboratorio": %d, "idUsuario": %d}
                """.formatted(laboratorio.getId(), usuario.getId());

        CountDownLatch aTravou = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);

        Future<?> a = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            laboratorioRepository.findParaReservaById(laboratorio.getId()).orElseThrow();
            aTravou.countDown();
            aguardar(liberarA);
        }));
        assertThat(aTravou.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            mockMvc.perform(post("/reservas").contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.title").value("Laboratório ocupado"))
                    // A mensagem da exceção traz o SQL executado; ela não deve chegar ao cliente
                    .andExpect(jsonPath("$.detail").value(not(containsStringIgnoringCase("select"))));
        } finally {
            liberarA.countDown();
            a.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void travarLaboratoriosEmOrdensOpostasGeraDeadlock() throws Exception {
        Long lab1 = novoLaboratorio().getId();
        Long lab2 = novoLaboratorio().getId();

        // Garante que cada transação já tem o primeiro lock antes de pedir o segundo
        CountDownLatch primeirosLocks = new CountDownLatch(2);

        Future<?> t1 = executor.submit(() -> travarEmOrdem(lab1, lab2, primeirosLocks));
        Future<?> t2 = executor.submit(() -> travarEmOrdem(lab2, lab1, primeirosLocks));

        // O Postgres detecta o ciclo após deadlock_timeout (1s por padrão) e aborta uma das duas
        Throwable erro1 = catchThrowable(() -> t1.get(10, TimeUnit.SECONDS));
        Throwable erro2 = catchThrowable(() -> t2.get(10, TimeUnit.SECONDS));

        assertThat(erro1 == null ^ erro2 == null)
                .as("exatamente uma transação deve ser abortada").isTrue();
        Throwable causa = (erro1 != null ? erro1 : erro2).getCause();
        assertThat(causa).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(sqlState(causa)).isEqualTo("40P01");
    }

    private void travarEmOrdem(Long primeiro, Long segundo, CountDownLatch primeirosLocks) {
        transactionTemplate.executeWithoutResult(status -> {
            laboratorioRepository.findParaReservaById(primeiro).orElseThrow();
            primeirosLocks.countDown();
            aguardar(primeirosLocks);
            laboratorioRepository.findParaReservaById(segundo).orElseThrow();
        });
    }

    private void aguardarSessaoEsperandoLock() throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < limite) {
            Integer esperando = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (esperando != null && esperando > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("nenhuma sessão ficou esperando lock em 10s");
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch não liberado em 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String sqlState(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    // Dados novos a cada execução, para não misturar com os dados do Postman
    private Laboratorio novoLaboratorio() {
        return laboratorioRepository.save(new Laboratorio("L" + System.nanoTime() % 100000, 10));
    }

    private Usuario novoUsuario() {
        return usuarioRepository.save(new Usuario("Teste", String.format("%011d", System.nanoTime() % 100_000_000_000L)));
    }
}
