package com.exercise.lockpessimist.reserva;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ReservaRepository extends JpaRepository<Reserva, Long > {

    @Query("SELECT COUNT(r) > 0 FROM Reserva r WHERE r.laboratorio.id = :laboratorioId" +
            " AND r.status = :status AND r.inicio < :novoFim AND r.fim > :novoInicio")
    boolean existeConflito(@Param("laboratorioId") Long idLaboratorio,
                                  @Param("novoInicio") Instant inicio,
                                  @Param("novoFim") Instant fim,
                                  @Param("status") ReservaStatus status);
}
