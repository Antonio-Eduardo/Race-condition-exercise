package com.exercise.lockpessimist.reserva;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReservaCancelarDto(@NotNull @Positive Long usuarioId) {
}
