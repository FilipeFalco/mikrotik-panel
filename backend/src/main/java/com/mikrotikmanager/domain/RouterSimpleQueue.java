package com.mikrotikmanager.domain;

/**
 * Read-only Simple Queue state used for ownership and reconciliation. A null
 * max limit means the RouterOS value was present but could not be parsed
 * safely; callers must not invent a value.
 */
public record RouterSimpleQueue(
        String id,
        String name,
        String comment,
        String target,
        SpeedLimit maxLimit,
        boolean disabled,
        boolean dynamic
) {
}
