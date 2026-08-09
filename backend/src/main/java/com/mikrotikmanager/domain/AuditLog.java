package com.mikrotikmanager.domain;

import java.time.Instant;

public record AuditLog(
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
