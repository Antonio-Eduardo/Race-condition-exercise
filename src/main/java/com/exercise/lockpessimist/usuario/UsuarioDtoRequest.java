package com.exercise.lockpessimist.usuario;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UsuarioDtoRequest(@Size(max = 50) @NotBlank String nome, @Size(max = 11)@NotBlank String cpf) {
}
