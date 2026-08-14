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
        boolean dynamic,
        boolean invalid,
        String parent,
        String limitAt,
        String priority,
        String queue,
        String burstLimit,
        String burstThreshold,
        String burstTime,
        String bucketSize,
        String time,
        String packetMarks,
        String dstAddress
) {
    /** Source compatible constructor for Phase 1-4 fixtures. */
    public RouterSimpleQueue(String id, String name, String comment, String target, SpeedLimit maxLimit,
                             boolean disabled, boolean dynamic) {
        this(id, name, comment, target, maxLimit, disabled, dynamic, false, "none", null, null, null,
                null, null, null, null, null, null, null);
    }
}
