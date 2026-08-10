package com.mikrotikmanager.domain;

/**
 * Result of comparing a locally declared future resource with the RouterOS
 * state observed in one snapshot. Reconciliation reports differences only;
 * it never corrects them.
 */
public enum ReconciliationStatus {
    IN_SYNC,
    MISSING,
    DRIFTED,
    CONFLICT,
    FOREIGN,
    AMBIGUOUS_OWNERSHIP,
    NOT_APPLICABLE
}
