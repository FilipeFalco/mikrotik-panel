package com.mikrotikmanager.domain;

import java.util.Objects;

/**
 * A fact checked against the snapshot used to produce a dry-run plan.
 *
 * <p>An unsatisfied {@link PlanSeverity#BLOCKING} precondition prevents the
 * plan from being ready for a future implementation. It never enables an
 * operation in Phase 3.</p>
 */
public record PlanPrecondition(
        String code,
        String description,
        boolean satisfied,
        PlanSeverity severity
) {
    public PlanPrecondition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(severity, "severity");
    }
}
