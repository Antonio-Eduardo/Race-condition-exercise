package com.exercise.lockpessimist.usuario;

import jakarta.validation.constraints.Size;

public record UsuarioUpdateDto(@Size(max = 50) String nome, @Size(max = 11) String cpf) {
}
