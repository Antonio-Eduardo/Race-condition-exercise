package com.exercise.lockpessimist.reserva;

import com.exercise.lockpessimist.config.RecursoNaoEncontradoException;
import com.exercise.lockpessimist.config.ReservaProperties;
import com.exercise.lockpessimist.laboratorio.Laboratorio;
import com.exercise.lockpessimist.laboratorio.LaboratorioRepository;
import com.exercise.lockpessimist.usuario.Usuario;
import com.exercise.lockpessimist.usuario.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ReservaService {

    private final ReservaRepository reservaRepository;
    private final UsuarioRepository usuarioRepository;
    private final LaboratorioRepository laboratorioRepository;
    private final Clock clock;
    private final ReservaProperties props;

    public ReservaService(ReservaRepository reservaRepository,
                          UsuarioRepository usuarioRepository,
                          LaboratorioRepository laboratorioRepository,
                          Clock clock,
                          ReservaProperties props) {
        this.reservaRepository = reservaRepository;
        this.usuarioRepository = usuarioRepository;
        this.laboratorioRepository = laboratorioRepository;
        this.clock = clock;
        this.props = props;
    }

    public List<ReservaDtoResponse> listarReservas() {
        Instant agora = Instant.now(clock);
        return reservaRepository.findAll().stream()
                .map(reserva -> ReservaDtoResponse.toResponse(reserva, agora)).toList();
    }

    public ReservaDtoResponse reservaPorId(Long id) {
        Reserva reserva = reservaRepository.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Reserva " + id + " não encontrada"));
        Instant agora = Instant.now(clock);
        return ReservaDtoResponse.toResponse(reserva, agora);
    }

    public ReservaDtoResponse criarReserva(ReservaDtoRequest request) {
        Duration duracao = Duration.between(request.inicio(), request.fim());
        if (duracao.compareTo(props.minimo()) < 0) {
            throw new ReservaDuracaoException("Duração é menor que a duração minima permitida. Duracao: " + duracao.toMinutes() + " minutos. Duração minima: " + props.minimo().toMinutes() + " minutos");
        } else if (duracao.compareTo(props.maximo()) > 0) {
            throw new ReservaDuracaoException("Duração é maior que a duração maxima permitida. Duração: " + duracao.toMinutes() + " minutos. Duração maxima: " + props.maximo().toMinutes() + " minutos");
        } else if (duracao.toSeconds() % props.bloco().toSeconds() > 0) {
            throw new ReservaDuracaoException("Duração deve ser multipla do bloco permitido. Duração: " + duracao.toMinutes() + " minutos. Duração bloco: " + props.bloco().toMinutes() + " minutos");
        } else if (request.inicio().getNano() != 0 || request.inicio().getEpochSecond() % props.bloco().toSeconds() > 0) {
            throw new ReservaDuracaoException("A reserva deve começar em um horario fechado. Inicio: " + request.inicio());
        }
        Laboratorio laboratorio = laboratorioRepository.findById(request.idLaboratorio())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Laboratorio " + request.idLaboratorio() + " não encontrado"));
        Usuario usuario = usuarioRepository.findById(request.idUsuario())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuario " + request.idUsuario() + " não encontrado"));
        Instant agora = Instant.now(clock);
        Reserva reserva = ReservaDtoRequest.toEntity(request, laboratorio, usuario);
        reserva.setCriadoEm(agora);
        reservaRepository.save(reserva);
        return ReservaDtoResponse.toResponse(reserva, agora);
    }

    //1. Não precisa de .save por causa do dirty checking do JPA/Hibernate
    //2. Metodo é um soft delete, reserva vou a ficar disponivel pela alteração no status
    @Transactional
    public ReservaDtoResponse cancelar(Long reservaId, ReservaCancelarDto reservaCancelarDto) {
        Reserva reserva = reservaRepository.findById(reservaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Reserva " + reservaId + " não encontrada"));
        Usuario usuario = usuarioRepository.findById(reservaCancelarDto.usuarioId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuario " + reservaCancelarDto.usuarioId() + " não encontrado"));

        Instant agora = Instant.now(clock);

        //chama metodo da entidade que retorna o status na hora de agora
        ReservaStatus statusAgora = reserva.statusEm(agora);

        //se a reserva for diferente de agendada, lança exceção e não deixa o soft delete acontecer
        if (statusAgora != ReservaStatus.AGENDADA) {
            throw new ReservaNaoCancelavelException("Reserva " + reservaId + " não pode ser cancelada: status " +
                    statusAgora);
        }

        reserva.setCanceladoEm(agora);
        reserva.setCanceladoPor(usuario);
        reserva.setStatus(ReservaStatus.CANCELADA);
        return ReservaDtoResponse.toResponse(reserva, agora);
    }
}
