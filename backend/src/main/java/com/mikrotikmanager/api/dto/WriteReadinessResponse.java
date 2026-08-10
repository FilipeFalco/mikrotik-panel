package com.mikrotikmanager.api.dto;

import java.time.Instant;
import java.util.List;

/** Phase 3 diagnostic response; it never signals RouterOS execution enabled. */
public record WriteReadinessResponse(
        Instant generatedAt,
        boolean mockMode,
        boolean writeFlagEnabled,
        boolean readyForFutureExecution,
        boolean executionEnabled,
        String phaseNotice,
        List<ReadinessCheckResponse> checks,
        ReadinessSummaryResponse summary
) {
    public WriteReadinessResponse {
        checks = List.copyOf(checks);
    }
}
