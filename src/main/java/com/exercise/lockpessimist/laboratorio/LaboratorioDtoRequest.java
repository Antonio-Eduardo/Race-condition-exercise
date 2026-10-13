package com.exercise.lockpessimist.laboratorio;

import jakarta.validation.constraints.NotBlank;

public record LaboratorioDtoRequest(@NotBlank String nomeLaboratorio, Integer computadores) {
}
