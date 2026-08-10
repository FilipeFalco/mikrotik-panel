package com.mikrotikmanager.domain;

/**
 * Intentions that can be analysed in Phase 3.
 *
 * <p>These values deliberately describe plans, not RouterOS commands. No
 * executor exists in this phase.</p>
 */
public enum PlannedOperationType {
    BLOCK_DEVICE,
    UNBLOCK_DEVICE,
    SET_DEVICE_SPEED,
    SET_PORT_SPEED
}
