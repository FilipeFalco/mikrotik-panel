package com.mikrotikmanager.domain;

/**
 * Safe summary of observed or desired state in a plan. It intentionally has
 * no transport DTO, raw RouterOS JSON, credential, or authorization data.
 */
public record PlanState(
        String displayName,
        String macAddress,
        String ipAddress,
        String interfaceName,
        String network,
        SpeedLimit speedLimit,
        Boolean blocked,
        BlockingStrategy blockingStrategy
) {
    public static PlanState unavailable() {
        return new PlanState(null, null, null, null, null, null, null, null);
    }
}
