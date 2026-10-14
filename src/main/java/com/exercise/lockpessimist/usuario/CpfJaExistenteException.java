package com.exercise.lockpessimist.usuario;

public class CpfJaExistenteException extends RuntimeException {
    public CpfJaExistenteException(String message) {
        super(message);
    }
}
