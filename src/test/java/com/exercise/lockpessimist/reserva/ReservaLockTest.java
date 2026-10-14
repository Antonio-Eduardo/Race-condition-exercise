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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.exercise.lockpessimist.reserva.ConcorrenciaTestes.aguardar;
import static com.exercise.lockpessimist.reserva.ConcorrenciaTestes.aguardarSessaoEsperandoLock;
import static com.exercise.lockpessimist.reserva.ConcorrenciaTestes.novoLaboratorio;
import static com.exercise.lockpessimist.reserva.ConcorrenciaTestes.novoUsuario;
import static com.exercise.lockpessimist.reserva.ConcorrenciaTestes.sqlState;
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
    private ReservaRepository reservaRepository;
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
        Laboratorio laboratorio = novoLaboratorio(laboratorioRepository);
        Usuario usuario = novoUsuario(usuarioRepository);
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
        aguardarSessaoEsperandoLock(jdbcTemplate);
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
        Laboratorio laboratorio = novoLaboratorio(laboratorioRepository);
        Usuario usuario = novoUsuario(usuarioRepository);
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
        Laboratorio laboratorio = novoLaboratorio(laboratorioRepository);
        Usuario usuario = novoUsuario(usuarioRepository);
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
    void pessimistaAceitaHorariosDiferentesNoMesmoLaboratorio() throws Exception {
        Laboratorio laboratorio = novoLaboratorio(laboratorioRepository);
        Usuario usuario = novoUsuario(usuarioRepository);
        ReservaDtoRequest manha = horario(laboratorio, usuario, "2030-01-10T08:00:00Z", "2030-01-10T10:00:00Z");
        ReservaDtoRequest tarde = horario(laboratorio, usuario, "2030-01-10T14:00:00Z", "2030-01-10T16:00:00Z");

        CountDownLatch aCriou = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);

        Future<?> a = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            reservaService.criarReserva(manha);
            aCriou.countDown();
            aguardar(liberarA);
        }));
        assertThat(aCriou.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> b = executor.submit(() -> reservaService.criarReserva(tarde));
        aguardarSessaoEsperandoLock(jdbcTemplate);

        liberarA.countDown();

        // B esperou, mas depois não encontrou conflito de horário: os dois gravam
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
        assertThat(reservasDoLaboratorio(laboratorio)).isEqualTo(2);
    }

    @Test
    void otimistaRecusaHorariosDiferentesNoMesmoLaboratorio() throws Exception {
        Laboratorio laboratorio = novoLaboratorio(laboratorioRepository);
        Usuario usuario = novoUsuario(usuarioRepository);
        ReservaDtoRequest manha = horario(laboratorio, usuario, "2030-01-10T08:00:00Z", "2030-01-10T10:00:00Z");
        ReservaDtoRequest tarde = horario(laboratorio, usuario, "2030-01-10T14:00:00Z", "2030-01-10T16:00:00Z");

        CountDownLatch aCriou = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);

        // A lê a versão e insere, mas só tenta o commit depois que B terminar
        Future<?> a = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            reservaService.criarReservaOtimista(manha);
            aCriou.countDown();
            aguardar(liberarA);
        }));
        assertThat(aCriou.await(10, TimeUnit.SECONDS)).isTrue();

        // B não espera ninguém: lê a mesma versão, grava e sobe a versão do laboratório
        reservaService.criarReservaOtimista(tarde);

        liberarA.countDown();

        // A perde no commit sem nenhum conflito de horário: falso conflito
        Throwable erroA = catchThrowable(() -> a.get(10, TimeUnit.SECONDS));
        assertThat(erroA).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(reservasDoLaboratorio(laboratorio)).isEqualTo(1);
    }

    @Test
    void travarLaboratoriosEmOrdensOpostasGeraDeadlock() throws Exception {
        Long lab1 = novoLaboratorio(laboratorioRepository).getId();
        Long lab2 = novoLaboratorio(laboratorioRepository).getId();

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

    private static ReservaDtoRequest horario(Laboratorio laboratorio, Usuario usuario, String inicio, String fim) {
        return new ReservaDtoRequest(Instant.parse(inicio), Instant.parse(fim), laboratorio.getId(), usuario.getId());
    }

    private long reservasDoLaboratorio(Laboratorio laboratorio) {
        return reservaRepository.findAll().stream()
                .filter(r -> r.getLaboratorio().getId().equals(laboratorio.getId()))
                .count();
    }
}
