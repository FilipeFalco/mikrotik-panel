package com.mikrotikmanager.api.dto;

/** Display-safe plan target. RouterOS .id is intentionally absent. */
public record PlanTargetResponse(
        String identifier,
        String displayName,
        String macAddress,
        String interfaceName
) {
}
