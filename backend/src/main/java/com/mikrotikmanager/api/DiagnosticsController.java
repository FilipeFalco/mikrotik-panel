package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.DiagnosticCheckResponse;
import com.mikrotikmanager.api.dto.DiagnosticsResponse;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.gateway.MikrotikGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {
    private final MikrotikGateway gateway;

    public DiagnosticsController(MikrotikGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping
    public DiagnosticsResponse diagnostics() {
        GatewayConnectionStatus status = gateway.connectionStatus();
        boolean available = status.connected();
        List<DiagnosticCheckResponse> checks = List.of(
                new DiagnosticCheckResponse("REST API", available, status.mockMode() ? "Simulado" : unavailableDetail(available)),
                new DiagnosticCheckResponse("DHCP", available, status.mockMode() ? "Dados simulados disponíveis" : unavailableDetail(available)),
                new DiagnosticCheckResponse("Queues", available, status.mockMode() ? "Dados simulados disponíveis" : unavailableDetail(available)),
                new DiagnosticCheckResponse("Interfaces", available, status.mockMode() ? "Dados simulados disponíveis" : unavailableDetail(available))
        );
        String warning = status.fastTrackDetected()
                ? "FastTrack detectado. Nas próximas fases, ele será analisado antes de aplicar limites reais."
                : null;
        return new DiagnosticsResponse(status.routerOsVersion(), status.connected(), status.latencyMillis(), status.mockMode(),
                status.fastTrackDetected(), checks, warning);
    }

    private String unavailableDetail(boolean available) {
        return available ? "Disponível" : "Indisponível";
    }
}
