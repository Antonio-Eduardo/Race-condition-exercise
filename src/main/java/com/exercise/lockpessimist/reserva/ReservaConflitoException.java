package com.exercise.lockpessimist.reserva;

public class ReservaConflitoException extends RuntimeException {
    public ReservaConflitoException(String message) {
        super(message);
    }
}
