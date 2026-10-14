package com.exercise.lockpessimist.reserva;

import com.exercise.lockpessimist.laboratorio.Laboratorio;
import com.exercise.lockpessimist.laboratorio.LaboratorioRepository;
import com.exercise.lockpessimist.usuario.Usuario;
import com.exercise.lockpessimist.usuario.UsuarioRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Utilitários compartilhados pelos testes que coordenam transações em threads diferentes
final class ConcorrenciaTestes {

    private ConcorrenciaTestes() {
    }

    // Prova pelo próprio Postgres que alguma sessão está parada esperando um lock, sem depender de sleep
    static void aguardarSessaoEsperandoLock(JdbcTemplate jdbcTemplate) throws InterruptedException {
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

    static void aguardar(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch não liberado em 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String sqlState(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    // Dados novos a cada execução, para não misturar com os dados do Postman
    static Laboratorio novoLaboratorio(LaboratorioRepository repository) {
        return repository.save(new Laboratorio("L" + System.nanoTime() % 100000, 10));
    }

    static Usuario novoUsuario(UsuarioRepository repository) {
        return repository.save(new Usuario("Teste", String.format("%011d", System.nanoTime() % 100_000_000_000L)));
    }
}
