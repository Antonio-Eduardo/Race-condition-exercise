package com.exercise.lockpessimist.usuario;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService){
        this.usuarioService = usuarioService;
    }

    @GetMapping
    public ResponseEntity<List<UsuarioDtoResponse>> listarUsuarios(){
        List<UsuarioDtoResponse> usuarios = usuarioService.listarUsuarios();
        return ResponseEntity.ok(usuarios);
    }
    @GetMapping("/{id}")
    public ResponseEntity<UsuarioDtoResponse> procurarUsuario(@PathVariable Long id){
        UsuarioDtoResponse response = usuarioService.getUsuario(id);
        return ResponseEntity.ok(response);
    }
    @PostMapping
    public ResponseEntity<UsuarioDtoResponse> criarUsuario(@Valid @RequestBody UsuarioDtoRequest request){
        UsuarioDtoResponse response = usuarioService.criarUsuario(request);
        URI uri = URI.create("/usuarios/" + response.id());
        return ResponseEntity.created(uri).body(response);
    }
    @PatchMapping("/{id}")
    public ResponseEntity<UsuarioDtoResponse> atualizarUsuario(@PathVariable Long id, @Valid @RequestBody UsuarioUpdateDto update){
        UsuarioDtoResponse response = usuarioService.atualizarUsuario(id,update);
        return ResponseEntity.ok(response);
    }
    @DeleteMapping("{id}")
    public ResponseEntity<Void> deletarUsuario(@PathVariable Long id){
        usuarioService.deletarUsuario(id);
        return ResponseEntity.noContent().build();
    }
}
