package com.mikrotikmanager.domain;

/**
 * Aggregate reconciliation facts exposed by the readiness screen. Counts are
 * intentionally summaries: the report does not persist or disclose raw
 * RouterOS configuration.
 */
public record ReadinessSummary(
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
    public ReadinessSummary {
        requireNonNegative(managed, "managed");
        requireNonNegative(foreign, "foreign");
        requireNonNegative(inSync, "inSync");
        requireNonNegative(drifted, "drifted");
        requireNonNegative(missing, "missing");
        requireNonNegative(conflicts, "conflicts");
        requireNonNegative(ambiguous, "ambiguous");
        requireNonNegative(managedPorts, "managedPorts");
        requireNonNegative(validManagedPorts, "validManagedPorts");
        if (validManagedPorts > managedPorts) {
            throw new IllegalArgumentException("validManagedPorts cannot exceed managedPorts");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
