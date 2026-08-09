package com.mikrotikmanager.domain;

import java.util.List;

/**
 * On-demand diagnostic result. It intentionally does not feed the frequent
 * system-status polling path, because firewall inspection can be expensive.
 */
public record GatewayDiagnostics(
        GatewayConnectionStatus connectionStatus,
        boolean fastTrackDetected,
        List<GatewayDiagnosticCheck> checks
) {
    public GatewayDiagnostics {
        checks = List.copyOf(checks);
    }
}
