package com.exercise.lockpessimist.reserva;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/reservas")
public class ReservaController {

    private final ReservaService reservaService;

    public ReservaController(ReservaService service){
        this.reservaService = service;
    }
    @GetMapping
    public ResponseEntity<List<ReservaDtoResponse>> listarReservas(){
        List<ReservaDtoResponse> response = reservaService.listarReservas();
        return ResponseEntity.ok(response);
    }
    @GetMapping("/{id}")
    public ResponseEntity<ReservaDtoResponse> procurarReserva(@PathVariable Long id){
        ReservaDtoResponse reservaDtoResponse = reservaService.reservaPorId(id);
        return ResponseEntity.ok(reservaDtoResponse);
    }
    @PostMapping
    public ResponseEntity<ReservaDtoResponse> criarReserva(@Valid@RequestBody ReservaDtoRequest request){
        ReservaDtoResponse reservaDtoResponse = reservaService.criarReserva(request);
        URI uri = URI.create("/reservas/"+reservaDtoResponse.id());
        return ResponseEntity.created(uri).body(reservaDtoResponse);
    }
    @PatchMapping("/{reservaId}/cancelar")
    public ResponseEntity<ReservaDtoResponse> cancelarReserva(@PathVariable Long reservaId, @Valid @RequestBody ReservaCancelarDto cancelarDto){
        ReservaDtoResponse response = reservaService.cancelar(reservaId,cancelarDto);
        return ResponseEntity.ok(response);
    }
    @PostMapping("/otimista")
    public ResponseEntity<ReservaDtoResponse> criarReservaOtimista(@Valid@RequestBody ReservaDtoRequest request){
        ReservaDtoResponse reservaDtoResponse = reservaService.criarReservaOtimista(request);
        URI uri = URI.create("/reservas/"+reservaDtoResponse.id());
        return ResponseEntity.created(uri).body(reservaDtoResponse);
    }
}
