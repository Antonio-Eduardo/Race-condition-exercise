package com.exercise.lockpessimist.reserva;

import com.exercise.lockpessimist.laboratorio.Laboratorio;
import com.exercise.lockpessimist.usuario.Usuario;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Duration;
import java.time.Instant;

public record ReservaDtoRequest(@FutureOrPresent @NotNull Instant inicio,
                                @FutureOrPresent @NotNull Instant fim,
                                @Positive @NotNull Long idLaboratorio,
                                @Positive @NotNull Long idUsuario) {
    public static Reserva toEntity(ReservaDtoRequest request, Laboratorio laboratorio, Usuario usuario){
       return new Reserva(
        request.inicio,
        request.fim,
        laboratorio,
        usuario,
        ReservaStatus.AGENDADA);
    }
}
