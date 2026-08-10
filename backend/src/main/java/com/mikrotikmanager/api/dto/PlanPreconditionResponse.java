package com.mikrotikmanager.api.dto;

/** A precondition reconstructed from the current local and RouterOS state. */
public record PlanPreconditionResponse(
        String code,
        String description,
        boolean satisfied,
        String severity
) {
}
