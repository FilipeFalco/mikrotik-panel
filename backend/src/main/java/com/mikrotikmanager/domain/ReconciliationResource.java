package com.mikrotikmanager.domain;

import java.util.List;
import java.util.Objects;

/**
 * A relevant expected resource and its observed state. RouterOS .id remains
 * internal to the snapshot and is never an operator-controlled identifier.
 */
public record ReconciliationResource(
        String resourceType,
        String resourceKey,
        String displayName,
        ResourceOwnership ownership,
        ReconciliationStatus status,
        String expectedName,
        String expectedTarget,
        String observedName,
        String observedTarget,
        boolean conflict,
        List<ReconciliationFinding> findings
) {
    public ReconciliationResource {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(status, "status");
        findings = List.copyOf(findings);
    }
}
