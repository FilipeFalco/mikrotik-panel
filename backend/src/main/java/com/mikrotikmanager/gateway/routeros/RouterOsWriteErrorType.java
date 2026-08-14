package com.mikrotikmanager.gateway.routeros;

/** Sanitized outcomes for the deliberately narrow Phase 4 writer. */
public enum RouterOsWriteErrorType {
    CREDENTIALS_MISSING,
    DEVICE_BLOCK_WRITES_DISABLED,
    BANDWIDTH_WRITES_DISABLED,
    GLOBAL_WRITES_DISABLED,
    PERMISSION_DENIED,
    NOT_FOUND,
    REJECTED,
    OUTCOME_UNKNOWN
}
