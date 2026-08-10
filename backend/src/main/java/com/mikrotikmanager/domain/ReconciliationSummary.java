package com.mikrotikmanager.domain;

/** Aggregated diagnostic counts for UI and readiness reports. */
public record ReconciliationSummary(
        int managed,
        int foreign,
        int inSync,
        int drifted,
        int missing,
        int conflicts,
        int ambiguous,
        int notApplicable
) {
}
