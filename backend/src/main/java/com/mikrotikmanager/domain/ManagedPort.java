package com.mikrotikmanager.domain;

import java.time.Instant;

public record ManagedPort(
        long id,
        String interfaceName,
        String friendlyName,
        String description,
        String network,
        String dhcpServer,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
