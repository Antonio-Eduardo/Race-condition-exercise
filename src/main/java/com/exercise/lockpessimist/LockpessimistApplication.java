package com.exercise.lockpessimist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LockpessimistApplication {

	public static void main(String[] args) {
		SpringApplication.run(LockpessimistApplication.class, args);
	}

}
