package com.mikrotikmanager.domain;

import java.util.Objects;

/**
 * A RouterOS resource which must not be adopted, replaced, or removed by a
 * future operation without an explicit ownership-safe design.
 */
public record PlanConflict(
        String code,
        String resourceType,
        String resourceName,
        String resourceTarget,
        ResourceOwnership ownership,
        String description,
        PlanSeverity severity
) {
    public PlanConflict {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(severity, "severity");
    }
}
