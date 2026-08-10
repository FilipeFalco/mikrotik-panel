package com.mikrotikmanager.api.dto;

/** Aggregated observed counts for the write-readiness screen. */
public record ReadinessSummaryResponse(
        int managed,
        int foreign,
        int inSync,
        int drifted,
        int missing,
        int conflicts,
        int ambiguous,
        int managedPorts,
        int validManagedPorts
) {
}
