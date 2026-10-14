package com.exercise.lockpessimist.reserva;

import com.exercise.lockpessimist.laboratorio.Laboratorio;
import com.exercise.lockpessimist.laboratorio.LaboratorioRepository;
import com.exercise.lockpessimist.usuario.Usuario;
import com.exercise.lockpessimist.usuario.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
        Resultado resultado = disputarMesmoHorario(reservaService::criarReserva);

        // Pessimista: quem perde espera o lock e recebe a resposta de negócio
        assertThat(resultado.sucessos()).isEqualTo(1);
        assertThat(resultado.conflitos()).isEqualTo(THREADS - 1);
        assertThat(resultado.reservasNoBanco()).isEqualTo(1);
    }

    @Test
    void lockOtimistaTambemDeixaApenasUmaReserva() throws InterruptedException {
        Resultado resultado = disputarMesmoHorario(reservaService::criarReservaOtimista);

        // Otimista: quem perde pode receber conflito de negócio (chegou depois do commit)
        // ou falha de versão (leu a mesma versão e perdeu no commit)
        assertThat(resultado.sucessos()).isEqualTo(1);
        assertThat(resultado.conflitos() + resultado.falhasDeVersao()).isEqualTo(THREADS - 1);
        assertThat(resultado.reservasNoBanco()).isEqualTo(1);
    }

    private Resultado disputarMesmoHorario(Function<ReservaDtoRequest, ReservaDtoResponse> criar) throws InterruptedException {
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
        AtomicInteger falhasDeVersao = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                largada.await();
                try {
                    criar.apply(request);
                    sucessos.incrementAndGet();
                } catch (ReservaConflitoException e) {
                    conflitos.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    falhasDeVersao.incrementAndGet();
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
        Resultado resultado = new Resultado(sucessos.get(), conflitos.get(), falhasDeVersao.get(), reservasNoBanco);
        System.out.println(resultado);

        // Se alguma thread falhou por outro motivo, a soma não fecha e o teste não mede a corrida
        assertThat(resultado.sucessos() + resultado.conflitos() + resultado.falhasDeVersao()).isEqualTo(THREADS);
        return resultado;
    }

    private record Resultado(int sucessos, int conflitos, int falhasDeVersao, long reservasNoBanco) {
    }
}
