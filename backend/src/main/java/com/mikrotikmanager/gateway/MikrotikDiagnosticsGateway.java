package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.GatewayDiagnostics;

/** Optional capability for gateways that can perform semantic, on-demand checks. */
public interface MikrotikDiagnosticsGateway {
    GatewayDiagnostics diagnostics();
}
