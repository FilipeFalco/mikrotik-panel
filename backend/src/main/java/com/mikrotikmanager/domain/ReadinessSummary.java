package com.mikrotikmanager.domain;

/**
 * Aggregate reconciliation facts exposed by the readiness screen. Counts are
 * intentionally summaries: the report does not persist or disclose raw
 * RouterOS configuration.
 *
 * <p>{@code managedPorts} represents only ports that are applicable candidates
 * for future bandwidth operations: a local CLIENT port that is enabled.
 * WAN ports and disabled CLIENT ports are legitimately {@code NOT_APPLICABLE}
 * and are intentionally excluded. {@code validManagedPorts} counts how many
 * applicable CLIENT ports have a present and parseable CIDR. Therefore an
 * enabled CLIENT port with an invalid or missing CIDR remains in
 * {@code managedPorts}, but not in {@code validManagedPorts}, and blocks
 * readiness.</p>
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
