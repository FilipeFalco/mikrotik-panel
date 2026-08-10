package com.mikrotikmanager.domain;

import java.util.Objects;

/** A non-conflict finding included with an operation plan. */
public record PlanWarning(
        String code,
        String description,
        PlanSeverity severity
) {
    public PlanWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(severity, "severity");
    }
}
