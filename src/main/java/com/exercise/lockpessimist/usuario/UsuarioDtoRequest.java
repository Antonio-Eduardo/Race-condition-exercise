package com.exercise.lockpessimist.usuario;

import jakarta.validation.constraints.NotBlank;

public record UsuarioDtoRequest(@NotBlank String nome, @NotBlank String cpf) {
}
