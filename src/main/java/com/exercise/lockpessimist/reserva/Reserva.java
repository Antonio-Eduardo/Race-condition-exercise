package com.exercise.lockpessimist.reserva;

import com.exercise.lockpessimist.laboratorio.Laboratorio;
import com.exercise.lockpessimist.usuario.Usuario;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;

@Entity
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Duration duracao;
    private Instant inicio;
    private Instant fim;

    @JoinColumn(name = "criado_em")
    private Instant criadoEm;

    @JoinColumn(name = "cancelado_em")
    private Instant cancelandoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelada_por")
    private Usuario canceladoPor;

    @Enumerated(EnumType.STRING)
    private ReservaStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "laboratorio_id")
    private Laboratorio laboratorio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    public Reserva(Duration duracao, Instant inicio, Instant fim, Laboratorio laboratorio, Usuario usuario) {
        this.duracao = duracao;
        this.inicio = inicio;
        this.fim = fim;
        this.laboratorio = laboratorio;
        this.usuario = usuario;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Duration getDuracao() {
        return duracao;
    }

    public void setDuracao(Duration duracao) {
        this.duracao = duracao;
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
}
