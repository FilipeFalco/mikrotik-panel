package com.mikrotikmanager.domain;

/**
 * Typed, untrusted user intent accepted by the planner. The backend rebuilds
 * all trusted state from a fresh RouterOS snapshot and local metadata.
 */
public sealed interface OperationIntent permits BlockDeviceIntent, UnblockDeviceIntent,
        SetDeviceSpeedIntent, SetPortSpeedIntent {

    PlannedOperationType operationType();

    String targetIdentifier();
}
