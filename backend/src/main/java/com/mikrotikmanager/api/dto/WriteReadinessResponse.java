package com.mikrotikmanager.api.dto;

import java.time.Instant;
import java.util.List;

/** Read-only capability and safety report for the Phase 4 device-block writer. */
public record WriteReadinessResponse(
        Instant generatedAt,
        boolean mockMode,
        boolean writeFlagEnabled,
        boolean readyForFutureExecution,
        boolean executionEnabled,
        String phaseNotice,
        List<ReadinessCheckResponse> checks,
        ReadinessSummaryResponse summary,
        boolean deviceBlockWriteFlagEnabled,
        boolean writeCredentialsConfigured,
        String blockingStrategy,
        boolean firewallOrderingAnalyzable
) {
    public WriteReadinessResponse {
        checks = List.copyOf(checks);
    }
}
