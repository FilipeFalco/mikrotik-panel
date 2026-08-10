package com.mikrotikmanager.api.dto;

/** Safe observed or desired-state summary for a dry-run plan. */
public record PlanStateResponse(
        String displayName,
        String macAddress,
        String ipAddress,
        String interfaceName,
        String network,
        SpeedLimitResponse speedLimit,
        Boolean blocked,
        String blockingStrategy
) {
}
