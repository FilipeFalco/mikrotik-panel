package com.mikrotikmanager.api.dto;

import com.mikrotikmanager.domain.DeviceStatus;

import java.time.Instant;

public record DeviceResponse(
        String displayName,
        String friendlyName,
        String hostname,
        String ipAddress,
        String macAddress,
        String interfaceName,
        String portFriendlyName,
        String dhcpServer,
        DeviceStatus status,
        boolean blocked,
        String leaseComment,
        String notes,
        long downloadLimitBps,
        long uploadLimitBps,
        long downloadTrafficBps,
        long uploadTrafficBps,
        Instant lastSeenAt
) {
}
