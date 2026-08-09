package com.mikrotikmanager.domain;

import java.time.Instant;

public record DeviceMetadata(
        long id,
        String macAddress,
        String friendlyName,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
}
