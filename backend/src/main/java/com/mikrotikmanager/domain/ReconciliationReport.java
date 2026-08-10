package com.mikrotikmanager.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, on-demand result of an observational reconciliation run. */
public record ReconciliationReport(
        Instant generatedAt,
        String snapshotFingerprint,
        int observedInterfaceCount,
        int observedDhcpServerCount,
        int observedLeaseCount,
        int observedSimpleQueueCount,
        boolean fastTrackDetected,
        List<ReconciliationResource> resources,
        ReconciliationSummary summary
) {
    public ReconciliationReport {
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(snapshotFingerprint, "snapshotFingerprint");
        resources = List.copyOf(resources);
        Objects.requireNonNull(summary, "summary");
    }
}
