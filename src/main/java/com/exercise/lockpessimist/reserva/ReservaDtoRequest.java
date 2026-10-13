package com.exercise.lockpessimist.reserva;

import java.time.Duration;
import java.time.Instant;

public record ReservaDtoRequest(Duration duracao, Instant inicio, Instant fim, Long idLaboratorio, Long idUsuario) {
}
