package com.mikrotikmanager.domain;

/** Intent to analyse a future device unblock, without executing it. */
public record UnblockDeviceIntent(String macAddress) implements OperationIntent {
    @Override
    public PlannedOperationType operationType() {
        return PlannedOperationType.UNBLOCK_DEVICE;
    }

    @Override
    public String targetIdentifier() {
        return macAddress;
    }
}
