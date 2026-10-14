package com.exercise.lockpessimist.config;

import com.exercise.lockpessimist.reserva.ReservaDuracaoException;
import com.exercise.lockpessimist.reserva.ReservaNaoCancelavelException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice //essa anotação faz o spring aplicar essa classe a todos os controllers
public class GlobalExceptionHandler {

    @ExceptionHandler(ReservaNaoCancelavelException.class)
    public ProblemDetail handleReservaNaoCancelavel(ReservaNaoCancelavelException ex){
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Reserva não pode ser cancelada");
        return problem;
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ProblemDetail handleRecursoNaoEncontrado(RecursoNaoEncontradoException ex){
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Recurso não encontrado");
        return problem;
    }

    @ExceptionHandler(ReservaDuracaoException.class)
    public ProblemDetail handleDuracaoInvalida(ReservaDuracaoException ex){
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Duração invalida");
        return problem;
    }
}
