package com.exercise.lockpessimist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("reserva.duracao")
public record ReservaProperties(Duration minimo, Duration maximo, Duration bloco) {
    
    public ReservaProperties {
        if (minimo == null || maximo == null || bloco == null) {
            throw new IllegalArgumentException("Duração da reserva não pode ser vazia.");

        }else if (!minimo.isPositive() || !maximo.isPositive() || !bloco.isPositive()) {
            throw new IllegalArgumentException("Duração da reserva não pode ser zero ou negativa.");

        } else if (minimo.toSeconds() % bloco.toSeconds() != 0 ||
                    maximo.toSeconds() % bloco.toSeconds() != 0) {
            throw new IllegalArgumentException("As durações devem ser multiplas dos blocos");

        }else if(minimo.compareTo(maximo) > 0){
            throw new IllegalArgumentException("A duração minima deve ser menor ou igual a maxima");
        }
    }
}
