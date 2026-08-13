package com.mikrotikmanager.domain;

/** The only device blocking mechanism supported by Phase 4. */
public enum BlockingStrategy {
    FIREWALL_MAC_RULE,
    /** Legacy value accepted only when decoding old diagnostic fixtures. */
    @Deprecated
    UNDECIDED
}
