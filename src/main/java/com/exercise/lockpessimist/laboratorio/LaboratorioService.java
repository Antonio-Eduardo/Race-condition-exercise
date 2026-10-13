package com.exercise.lockpessimist.laboratorio;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

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
        Laboratorio laboratorio = laboratorioRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Laboratorio Não encontrado " + id));
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
        Laboratorio laboratorio = laboratorioRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Laboratorio não encontrado " + id));

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
        Laboratorio laboratorio = laboratorioRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Laboratorio não encontrado " + id));
        laboratorioRepository.delete(laboratorio);
    }
}
