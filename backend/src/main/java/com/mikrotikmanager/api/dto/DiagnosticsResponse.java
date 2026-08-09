package com.mikrotikmanager.api.dto;

import java.util.List;

public record DiagnosticsResponse(
        String routerOsVersion,
        boolean connected,
        Long latencyMillis,
        boolean mockMode,
        boolean fastTrackDetected,
        List<DiagnosticCheckResponse> checks,
        String warning
) {
}
