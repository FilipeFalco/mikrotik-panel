package com.mikrotikmanager.domain;

import java.time.Instant;

public record RouterDevice(
        String leaseId,
        String macAddress,
        String hostname,
        String ipAddress,
        String dhcpServer,
        String interfaceName,
        DeviceStatus status,
        boolean blocked,
        String leaseComment,
        SpeedLimit speedLimit,
        TrafficRate traffic,
        Instant lastSeenAt
) {
}
