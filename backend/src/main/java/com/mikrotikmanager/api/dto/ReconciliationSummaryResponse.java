package com.mikrotikmanager.api.dto;

/** Aggregated reconciliation counts for diagnostics UI. */
public record ReconciliationSummaryResponse(
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
