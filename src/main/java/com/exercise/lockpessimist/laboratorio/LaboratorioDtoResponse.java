package com.exercise.lockpessimist.laboratorio;

public record LaboratorioDtoResponse(Long id, String nomeLaboratorio, Integer computadores) {
    public static LaboratorioDtoResponse toResponse(Laboratorio laboratorio){
        return new LaboratorioDtoResponse(laboratorio.getId(), laboratorio.getNomeLaboratorio(), laboratorio.getComputadores());
    }
}
