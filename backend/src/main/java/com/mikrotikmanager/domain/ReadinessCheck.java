package com.mikrotikmanager.domain;

import java.util.Objects;

/**
 * One safe, human-readable fact checked while assessing future write
 * readiness. The detail must be a summary, never a raw RouterOS response or
 * credential-bearing transport data.
 */
public record ReadinessCheck(
        String code,
        String description,
        boolean satisfied,
        ReadinessSeverity severity,
        String detail
) {
    public ReadinessCheck {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(detail, "detail");
    }
}
