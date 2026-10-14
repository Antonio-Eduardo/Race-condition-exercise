package com.exercise.lockpessimist.usuario;

import com.exercise.lockpessimist.config.RecursoNaoEncontradoException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Usuario " + id + " não encontrado"));
        return UsuarioDtoResponse.toResponse(usuario);
    }
    @Transactional
    public UsuarioDtoResponse atualizarUsuario(Long id, UsuarioUpdateDto usuarioDtoRequest){
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Usuario " + id + " não encontrado"));

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
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Usuario " + id + " não encontrado"));
        usuarioRepository.delete(usuario);
    }
}
