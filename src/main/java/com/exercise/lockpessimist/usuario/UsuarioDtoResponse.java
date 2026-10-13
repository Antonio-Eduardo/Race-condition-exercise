package com.exercise.lockpessimist.usuario;

public record UsuarioDtoResponse(Long id, String nome, String cpf) {

    public static UsuarioDtoResponse toResponse(Usuario usuario){
        return new UsuarioDtoResponse(usuario.getId(),
                usuario.getNome(),
                usuario.getCpf());
    }
}
