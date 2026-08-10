package com.mikrotikmanager.domain;

/**
 * Safe, display-oriented identity of the subject of a plan. RouterOS .id
 * values are deliberately not used as an operator-controlled target.
 */
public record PlanTarget(
        String identifier,
        String displayName,
        String macAddress,
        String interfaceName
) {
}
