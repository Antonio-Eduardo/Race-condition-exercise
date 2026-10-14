package com.exercise.lockpessimist.reserva;

import com.exercise.lockpessimist.laboratorio.Laboratorio;
import com.exercise.lockpessimist.laboratorio.LaboratorioRepository;
import com.exercise.lockpessimist.usuario.Usuario;
import com.exercise.lockpessimist.usuario.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Sem @Transactional: as reservas são criadas por outras threads, cada uma com sua própria transação
@SpringBootTest
class ReservaConcorrenciaTest {

    private static final int THREADS = 10;

    @Autowired
    private ReservaService reservaService;
    @Autowired
    private ReservaRepository reservaRepository;
    @Autowired
    private LaboratorioRepository laboratorioRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    void apenasUmaReservaDeveSerCriadaParaOMesmoHorario() throws InterruptedException {
        // Laboratório e usuário novos a cada execução, para não misturar com os dados do Postman
        long sufixo = System.nanoTime();
        Laboratorio laboratorio = laboratorioRepository.save(new Laboratorio("T" + sufixo % 100000, 10));
        Usuario usuario = usuarioRepository.save(new Usuario("Teste", String.format("%011d", sufixo % 100_000_000_000L)));

        ReservaDtoRequest request = new ReservaDtoRequest(
                Instant.parse("2030-01-10T14:00:00Z"),
                Instant.parse("2030-01-10T16:00:00Z"),
                laboratorio.getId(),
                usuario.getId());

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger sucessos = new AtomicInteger();
        AtomicInteger conflitos = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                largada.await();
                try {
                    reservaService.criarReserva(request);
                    sucessos.incrementAndGet();
                } catch (ReservaConflitoException e) {
                    conflitos.incrementAndGet();
                }
                return null;
            });
        }

        largada.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long reservasNoBanco = reservaRepository.findAll().stream()
                .filter(r -> r.getLaboratorio().getId().equals(laboratorio.getId()))
                .count();
        System.out.printf("sucessos=%d conflitos=%d reservasNoBanco=%d%n", sucessos.get(), conflitos.get(), reservasNoBanco);

        // Se alguma thread falhou por outro motivo, a soma não fecha e o teste não mede a corrida
        assertThat(sucessos.get() + conflitos.get()).isEqualTo(THREADS);
        assertThat(sucessos.get()).isEqualTo(1);
        assertThat(reservasNoBanco).isEqualTo(1);
    }
}
