package com.exercise.lockpessimist.laboratorio;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record LaboratorioUpdateDto(@Size(max = 6) String nomeLaboratorio,@PositiveOrZero Integer computadores) {
}
