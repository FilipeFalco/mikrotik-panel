package com.mikrotikmanager.api.dto;

/** Summary of a resource that a future executor must not adopt or alter. */
public record PlanConflictResponse(
        String code,
        String resourceType,
        String resourceName,
        String resourceTarget,
        String ownership,
        String description,
        String severity
) {
}
