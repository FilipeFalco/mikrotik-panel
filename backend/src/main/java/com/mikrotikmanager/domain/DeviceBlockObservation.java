package com.mikrotikmanager.domain;

/** Batched effective block state derived from the current RouterOS snapshot. */
public record DeviceBlockObservation(
        boolean blocked,
        ResourceOwnership ownership,
        String source
) {
}
