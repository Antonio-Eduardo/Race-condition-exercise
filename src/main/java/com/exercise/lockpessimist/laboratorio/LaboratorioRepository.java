package com.exercise.lockpessimist.laboratorio;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.Optional;

public interface LaboratorioRepository extends JpaRepository<Laboratorio, Long> {

    //trava a linha do laboratório até o fim da transação; outras chamadas deste  método esperam
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000")})
    Optional<Laboratorio> findParaReservaById(Long id);
}
