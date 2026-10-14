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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Insere reservas direto pelo repository, sem passar pelo lock do service: simula o código que esquece o lock
@SpringBootTest
@AutoConfigureMockMvc
class ReservaConstraintTest {

    @Autowired
    private ReservaRepository reservaRepository;
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
    void bancoRecusaReservaSobrepostaMesmoSemLock() {
        Laboratorio laboratorio = novoLaboratorio(laboratorioRepository);
        Usuario usuario = novoUsuario(usuarioRepository);
        reservaRepository.save(agendada(laboratorio, usuario, "2030-01-10T14:00:00Z", "2030-01-10T16:00:00Z"));

        Throwable erro = catchThrowable(() -> reservaRepository.save(
                agendada(laboratorio, usuario, "2030-01-10T15:00:00Z", "2030-01-10T17:00:00Z")));

        assertThat(erro).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sqlState(erro)).isEqualTo("23P01");
    }

    @Test
    void bancoAceitaHorarioColadoEHorarioDeReservaCancelada() {
        Laboratorio laboratorio = novoLaboratorio(laboratorioRepository);
        Usuario usuario = novoUsuario(usuarioRepository);
        reservaRepository.save(agendada(laboratorio, usuario, "2030-01-10T14:00:00Z", "2030-01-10T16:00:00Z"));

        // '[)': a reserva das 14h às 16h não inclui as 16h, então 16h-18h não se sobrepõe
        reservaRepository.save(agendada(laboratorio, usuario, "2030-01-10T16:00:00Z", "2030-01-10T18:00:00Z"));

        // WHERE status = 'AGENDADA': reservas canceladas ficam fora da constraint
        Reserva cancelada = agendada(laboratorio, usuario, "2030-01-10T14:00:00Z", "2030-01-10T16:00:00Z");
        cancelada.setStatus(ReservaStatus.CANCELADA);
        cancelada.setCanceladoEm(Instant.now());
        cancelada.setCanceladoPor(usuario);
        reservaRepository.save(cancelada);

        long reservasNoBanco = reservaRepository.findAll().stream()
                .filter(r -> r.getLaboratorio().getId().equals(laboratorio.getId()))
                .count();
        assertThat(reservasNoBanco).isEqualTo(3);
    }

    @Test
    void conflitoDetectadoPeloBancoViraHttp409() throws Exception {
        Laboratorio laboratorio = novoLaboratorio(laboratorioRepository);
        Usuario usuario = novoUsuario(usuarioRepository);
        String corpo = """
                {"inicio": "2030-01-10T14:00:00Z", "fim": "2030-01-10T16:00:00Z", "idLaboratorio": %d, "idUsuario": %d}
                """.formatted(laboratorio.getId(), usuario.getId());

        CountDownLatch aInseriu = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);

        // A insere sem lock e segura o commit: o existeConflito da API não enxerga essa reserva
        Future<?> a = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            reservaRepository.save(agendada(laboratorio, usuario, "2030-01-10T14:00:00Z", "2030-01-10T16:00:00Z"));
            aInseriu.countDown();
            aguardar(liberarA);
        }));
        assertThat(aInseriu.await(10, TimeUnit.SECONDS)).isTrue();

        // B passa pelo lock e pelo existeConflito; o INSERT espera a transação de A para saber se conflita
        Future<MockHttpServletResponse> b = executor.submit(() -> mockMvc
                .perform(post("/reservas").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andReturn().getResponse());
        aguardarSessaoEsperandoLock(jdbcTemplate);

        liberarA.countDown();
        a.get(10, TimeUnit.SECONDS);

        // A commitou: a constraint recusa B com 23P01, e o handler responde igual ao conflito detectado pela aplicação
        MockHttpServletResponse resposta = b.get(10, TimeUnit.SECONDS);
        assertThat(resposta.getStatus()).isEqualTo(409);
        assertThat(resposta.getContentAsString()).contains("Reserva conflitante");
    }

    private static Reserva agendada(Laboratorio laboratorio, Usuario usuario, String inicio, String fim) {
        Reserva reserva = new Reserva(Instant.parse(inicio), Instant.parse(fim), laboratorio, usuario, ReservaStatus.AGENDADA);
        reserva.setCriadoEm(Instant.now());
        return reserva;
    }
}
