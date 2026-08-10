package com.mikrotikmanager.domain;

/**
 * Impact of a finding in the future-write readiness diagnostic.
 *
 * <p>This is deliberately diagnostic-only. A {@link #BLOCKING} finding can
 * make a future implementation unsafe, but it never authorizes or triggers a
 * RouterOS mutation in Phase 3.</p>
 */
public enum ReadinessSeverity {
    INFO,
    WARNING,
    BLOCKING
}
