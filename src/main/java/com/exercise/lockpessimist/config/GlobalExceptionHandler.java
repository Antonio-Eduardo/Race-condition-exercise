package com.exercise.lockpessimist.config;

import com.exercise.lockpessimist.reserva.ReservaConflitoException;
import com.exercise.lockpessimist.reserva.ReservaDuracaoException;
import com.exercise.lockpessimist.reserva.ReservaNaoCancelavelException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    @ExceptionHandler(ReservaConflitoException.class)
    public ProblemDetail handleReservaConflitante(ReservaConflitoException ex){
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Reserva conflitante");
        return problem;
    }
    @ExceptionHandler(CannotAcquireLockException.class)
    public ProblemDetail handleLockTimeOut(CannotAcquireLockException ex){
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "O laboratório está sendo reservado por outra requisição. Tente novamente.");
        problem.setTitle("Laboratório ocupado");
        return problem;
    }
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockFailure(ObjectOptimisticLockingFailureException ex){
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "O laboratório está sendo reservado por outra requisição. Tente novamente.");
        problem.setTitle("Laboratorio ocupado");
        return problem;
    }
}
