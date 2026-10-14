package com.exercise.lockpessimist.reserva;

import java.time.Instant;

public record ReservaDtoResponse(Long id,
                                 Long duracaoMinutos,
                                 Instant inicio,
                                 Instant fim,
                                 Instant criadoEm,
                                 Long idLaboratorio,
                                 Long idUsuario,
                                 ReservaStatus status) {

    public static ReservaDtoResponse toResponse(Reserva reserva, Instant agora){
        return new ReservaDtoResponse(
                reserva.getId(), reserva.getDuration().toMinutes(), reserva.getInicio(), reserva.getFim(), reserva.getCriadoEm(),
                reserva.getLaboratorio().getId(),reserva.getUsuario().getId(),reserva.statusEm(agora)
        );
    }
}
