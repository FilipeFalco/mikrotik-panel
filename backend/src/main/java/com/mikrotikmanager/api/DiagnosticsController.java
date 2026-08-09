package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.DiagnosticCheckResponse;
import com.mikrotikmanager.api.dto.DiagnosticsResponse;
import com.mikrotikmanager.domain.GatewayDiagnosticCheck;
import com.mikrotikmanager.domain.GatewayDiagnostics;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.MikrotikDiagnosticsGateway;
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
        if (gateway instanceof MikrotikDiagnosticsGateway diagnosticsGateway) {
            return response(diagnosticsGateway.diagnostics());
        }

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

    private DiagnosticsResponse response(GatewayDiagnostics diagnostics) {
        GatewayConnectionStatus status = diagnostics.connectionStatus();
        List<DiagnosticCheckResponse> checks = diagnostics.checks().stream()
                .map(this::response)
                .toList();
        String warning = diagnostics.fastTrackDetected()
                ? "FastTrack detectado. A configuração pode interferir com limites de banda futuros. Nenhuma regra foi alterada."
                : null;
        return new DiagnosticsResponse(status.routerOsVersion(), status.connected(), status.latencyMillis(), status.mockMode(),
                diagnostics.fastTrackDetected(), checks, warning);
    }

    private DiagnosticCheckResponse response(GatewayDiagnosticCheck check) {
        return new DiagnosticCheckResponse(check.name(), check.available(), check.detail());
    }

    private String unavailableDetail(boolean available) {
        return available ? "Disponível" : "Indisponível";
    }
}
