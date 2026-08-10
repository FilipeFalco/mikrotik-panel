package com.mikrotikmanager.domain;

/** Intent to analyse a future per-port speed-limit change. */
public record SetPortSpeedIntent(String interfaceName, SpeedLimit requestedLimit) implements OperationIntent {
    @Override
    public PlannedOperationType operationType() {
        return PlannedOperationType.SET_PORT_SPEED;
    }

    @Override
    public String targetIdentifier() {
        return interfaceName;
    }
}
