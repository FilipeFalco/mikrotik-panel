package com.mikrotikmanager.domain;

import java.util.Objects;

/** A concise, safe explanation for one reconciliation resource. */
public record ReconciliationFinding(
        String code,
        String description,
        PlanSeverity severity
) {
    public ReconciliationFinding {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(severity, "severity");
    }
}
