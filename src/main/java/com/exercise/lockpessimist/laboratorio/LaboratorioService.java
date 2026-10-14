package com.exercise.lockpessimist.laboratorio;

import com.exercise.lockpessimist.config.RecursoNaoEncontradoException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LaboratorioService {

    private final LaboratorioRepository laboratorioRepository;

    public LaboratorioService( LaboratorioRepository laboratorioRepository){
        this.laboratorioRepository = laboratorioRepository;
    }

    @Transactional
    public List<LaboratorioDtoResponse> listarLaboratorios(){
        return laboratorioRepository.findAll().stream().map(LaboratorioDtoResponse::toResponse).toList();
    }
    @Transactional
    public LaboratorioDtoResponse laboratorioPorId(Long id){
        Laboratorio laboratorio = laboratorioRepository.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Laboratorio " + id + " não encontrado"));
        return LaboratorioDtoResponse.toResponse(laboratorio);
    }

    @Transactional
    public LaboratorioDtoResponse criarLaboratorio(LaboratorioDtoRequest laboratorioDtoRequest){
        Laboratorio laboratorio = new Laboratorio();
        laboratorio.setNomeLaboratorio(laboratorioDtoRequest.nomeLaboratorio());
        laboratorio.setComputadores(laboratorioDtoRequest.computadores());
        laboratorioRepository.save(laboratorio);
        return LaboratorioDtoResponse.toResponse(laboratorio);
    }

    @Transactional
    public LaboratorioDtoResponse atualizarLaboratorio(Long id, LaboratorioUpdateDto request){
        Laboratorio laboratorio = laboratorioRepository.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Laboratorio " + id + " não encontrado"));

        laboratorio.setNomeLaboratorio(request.nomeLaboratorio() != null
                ? request.nomeLaboratorio()
                : laboratorio.getNomeLaboratorio());
        laboratorio.setComputadores(request.computadores() != null
                ? request.computadores()
                : laboratorio.getComputadores());

        laboratorioRepository.save(laboratorio);
        return LaboratorioDtoResponse.toResponse(laboratorio);
    }
    @Transactional
    public void deletarLaboratorio(Long id){
        Laboratorio laboratorio = laboratorioRepository.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Laboratorio " + id + " não encontrado"));
        laboratorioRepository.delete(laboratorio);
    }
}
