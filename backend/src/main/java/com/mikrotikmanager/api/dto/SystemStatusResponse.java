package com.mikrotikmanager.api.dto;

public record SystemStatusResponse(
        boolean connected,
        boolean mockMode,
        String host,
        int port,
        String routerOsVersion,
        Long latencyMillis,
        String message,
        boolean readOnly,
        boolean fastTrackDetected,
        boolean deviceBlockExecutionEnabled
) {
}
