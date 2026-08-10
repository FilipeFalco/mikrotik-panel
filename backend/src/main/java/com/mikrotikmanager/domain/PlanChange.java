package com.mikrotikmanager.domain;

import java.util.Objects;

/**
 * Human-readable abstract change a later phase could consider after it has
 * independently revalidated the plan. It is not a RouterOS request.
 */
public record PlanChange(
        String action,
        String resourceType,
        String description
) {
    public PlanChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(description, "description");
    }
}
