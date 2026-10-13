package com.exercise.lockpessimist.usuario;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;

    public UsuarioService ( UsuarioRepository usuarioRepository){
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional
    public UsuarioDtoResponse criarUsuario(UsuarioDtoRequest usuarioDtoRequest){
        Usuario usuario = new Usuario();
        usuario.setNome(usuarioDtoRequest.nome());
        usuario.setCpf(usuarioDtoRequest.cpf());
        usuarioRepository.save(usuario);
        return UsuarioDtoResponse.toResponse(usuario);
    }

    @Transactional
    public List<UsuarioDtoResponse> listarUsuarios(){
        return usuarioRepository.findAll().stream().map(UsuarioDtoResponse::toResponse).toList();
    }

    @Transactional
    public UsuarioDtoResponse getUsuario(Long id){
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Usuario não encontrado " + id));
        return UsuarioDtoResponse.toResponse(usuario);
    }
    @Transactional
    public UsuarioDtoResponse atualizarUsuario(Long id, UsuarioUpdateDto usuarioDtoRequest){
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Usuario não encontrado " + id));

        usuario.setNome(usuarioDtoRequest.nome() != null
                ? usuarioDtoRequest.nome()
                :usuario.getNome());
        usuario.setCpf(usuarioDtoRequest.cpf() != null
                ? usuarioDtoRequest.cpf()
                : usuario.getCpf());

        usuarioRepository.save(usuario);
        return UsuarioDtoResponse.toResponse(usuario);
    }
    @Transactional
    public void deletarUsuario(Long id){
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Usuario não encontrado " + id));
        usuarioRepository.delete(usuario);
    }
}
