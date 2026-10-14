package com.exercise.lockpessimist.laboratorio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record LaboratorioDtoRequest(@Size(max = 6) @NotBlank String nomeLaboratorio,@PositiveOrZero @NotNull Integer computadores) {
}
