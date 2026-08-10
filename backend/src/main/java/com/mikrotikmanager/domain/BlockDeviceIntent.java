package com.mikrotikmanager.domain;

/** Intent to analyse a future device block, without executing it. */
public record BlockDeviceIntent(String macAddress) implements OperationIntent {
    @Override
    public PlannedOperationType operationType() {
        return PlannedOperationType.BLOCK_DEVICE;
    }

    @Override
    public String targetIdentifier() {
        return macAddress;
    }
}
