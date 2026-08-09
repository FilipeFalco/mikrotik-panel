package com.mikrotikmanager.api.dto;

import java.time.Instant;

public record AuditLogResponse(
        long id,
        Instant createdAt,
        String action,
        String targetType,
        String targetIdentifier,
        String previousValue,
        String newValue,
        boolean success,
        String errorMessage
) {
}
