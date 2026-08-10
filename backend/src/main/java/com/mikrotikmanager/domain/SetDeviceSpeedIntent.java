package com.mikrotikmanager.domain;

/** Intent to analyse a future per-device speed-limit change. */
public record SetDeviceSpeedIntent(String macAddress, SpeedLimit requestedLimit) implements OperationIntent {
    @Override
    public PlannedOperationType operationType() {
        return PlannedOperationType.SET_DEVICE_SPEED;
    }

    @Override
    public String targetIdentifier() {
        return macAddress;
    }
}
