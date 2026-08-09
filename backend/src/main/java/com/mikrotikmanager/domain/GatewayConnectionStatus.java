package com.mikrotikmanager.domain;

public record GatewayConnectionStatus(
        boolean connected,
        boolean mockMode,
        String host,
        int port,
        String routerOsVersion,
        Long latencyMillis,
        String message,
        boolean readOnly,
        boolean fastTrackDetected
) {
}
