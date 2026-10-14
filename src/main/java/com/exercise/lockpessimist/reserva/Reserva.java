package com.exercise.lockpessimist.reserva;

import com.exercise.lockpessimist.laboratorio.Laboratorio;
import com.exercise.lockpessimist.usuario.Usuario;
import jakarta.persistence.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Entity
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant inicio;
    private Instant fim;

    @Column(name = "criado_em")
    private Instant criadoEm;

    @Column(name = "cancelado_em")
    private Instant canceladoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelado_por")
    private Usuario canceladoPor;

    @Enumerated(EnumType.STRING)
    private ReservaStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "laboratorio_id")
    private Laboratorio laboratorio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    public Reserva(Instant inicio, Instant fim, Laboratorio laboratorio, Usuario usuario, ReservaStatus status) {
        this.inicio = inicio;
        this.fim = fim;
        this.laboratorio = laboratorio;
        this.usuario = usuario;
        this.status = status;
    }

    public Reserva(){}

    public ReservaStatus statusEm(Instant agora){
        return status == ReservaStatus.CANCELADA
                ? ReservaStatus.CANCELADA : agora.isBefore(inicio)
                ? ReservaStatus.AGENDADA : agora.isBefore(fim)
                ? ReservaStatus.EM_ANDAMENTO : ReservaStatus.ENCERRADA;

        /*como seria com if/else :

        if (getStatus() == ReservaStatus.CANCELADA){
            return ReservaStatus.CANCELADA;
        } else if (agora.isBefore(inicio)){
            return  ReservaStatus.AGENDADA;
        } else if(agora.isBefore(fim)){
            return ReservaStatus.EM_ANDAMENTO;
        } else {
            return ReservaStatus.ENCERRADA;
        }
        */
    }
    public Duration getDuration(){
        return Duration.between(inicio,fim);
    }
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getInicio() {
        return inicio;
    }

    public void setInicio(Instant inicio) {
        this.inicio = inicio;
    }

    public Instant getFim() {
        return fim;
    }

    public void setFim(Instant fim) {
        this.fim = fim;
    }

    public Laboratorio getLaboratorio() {
        return laboratorio;
    }

    public void setLaboratorio(Laboratorio laboratorio) {
        this.laboratorio = laboratorio;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public void setCriadoEm(Instant criadoEm) {
        this.criadoEm = criadoEm;
    }

    public Instant getCanceladoEm() {
        return canceladoEm;
    }

    public void setCanceladoEm(Instant canceladoEm) {
        this.canceladoEm = canceladoEm;
    }

    public ReservaStatus getStatus() {
        return status;
    }

    public void setStatus(ReservaStatus status) {
        this.status = status;
    }

    public Usuario getCanceladoPor() {
        return canceladoPor;
    }

    public void setCanceladoPor(Usuario canceladoPor) {
        this.canceladoPor = canceladoPor;
    }
}
