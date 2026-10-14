package com.exercise.lockpessimist.reserva;

public class ReservaNaoCancelavelException extends RuntimeException {
    public ReservaNaoCancelavelException(String message) {
        super(message);
    }
}
