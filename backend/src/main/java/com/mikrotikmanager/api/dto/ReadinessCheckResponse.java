package com.mikrotikmanager.api.dto;

/** Safe, human-readable write-readiness diagnostic check. */
public record ReadinessCheckResponse(
        String code,
        String description,
        boolean satisfied,
        String severity,
        String detail
) {
}
