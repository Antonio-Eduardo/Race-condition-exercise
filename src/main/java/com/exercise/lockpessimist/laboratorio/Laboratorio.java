package com.exercise.lockpessimist.laboratorio;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Laboratorio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nomeLaboratorio;
    private Integer computadores;

    public Laboratorio(String nomeLaboratorio, Integer computadores){
        this.nomeLaboratorio = nomeLaboratorio;
        this.computadores = computadores;
    }
    public Laboratorio(){}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNomeLaboratorio() {
        return nomeLaboratorio;
    }

    public void setNomeLaboratorio(String nomeLaboratorio) {
        this.nomeLaboratorio = nomeLaboratorio;
    }

    public Integer getComputadores() {
        return computadores;
    }

    public void setComputadores(Integer computadores) {
        this.computadores = computadores;
    }
}
