package com.exercise.lockpessimist.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    //1. Clock como bean: o tempo vira dependência injetável, não chamada global (Instant.now())
    //2. Testes: permite usar Clock.fixed/offset para ter datas previsíveis e simular passagem do tempo
    //3. Fuso centralizado em UTC: LocalDateTime/LocalDate.now(clock) não dependem do fuso do servidor
    //4. Fonte única de tempo: trocar a origem do tempo exige mudar só este bean
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
