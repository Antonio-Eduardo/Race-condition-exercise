package com.exercise.lockpessimist.laboratorio;

import jakarta.validation.Valid;
import org.apache.coyote.Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/laboratorios")
public class LaboratorioController {

    private final LaboratorioService laboratorioService;

    public LaboratorioController(LaboratorioService laboratorioService){
        this.laboratorioService = laboratorioService;
    }

    @GetMapping
    public ResponseEntity<List<LaboratorioDtoResponse>> listarLaboratorios(){
        List<LaboratorioDtoResponse> responses = laboratorioService.listarLaboratorios();
        return ResponseEntity.ok(responses);
    }
    @GetMapping("/{id}")
    public ResponseEntity<LaboratorioDtoResponse> procurarLaboratorio(@PathVariable Long id){
        LaboratorioDtoResponse response = laboratorioService.laboratorioPorId(id);
        return ResponseEntity.ok(response);
    }
    @PostMapping
    public ResponseEntity<LaboratorioDtoResponse> criarLaboratorio(@Valid @RequestBody LaboratorioDtoRequest request){
        LaboratorioDtoResponse response = laboratorioService.criarLaboratorio(request);
        URI uri = URI.create("/laboratorios/" + response.id());
        return ResponseEntity.created(uri).body(response);
    }
    @PatchMapping("/{id}")
    public ResponseEntity<LaboratorioDtoResponse> atualizarLaboratorio(@PathVariable Long id, @Valid @RequestBody LaboratorioUpdateDto updateDto){
        LaboratorioDtoResponse response = laboratorioService.atualizarLaboratorio(id,updateDto);
        return ResponseEntity.ok(response);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletarLaboratorio(@PathVariable Long id){
        laboratorioService.deletarLaboratorio(id);
        return ResponseEntity.noContent().build();
    }
}
