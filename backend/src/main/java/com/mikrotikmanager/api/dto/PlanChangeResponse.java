package com.mikrotikmanager.api.dto;

/** Abstract candidate change. It is never a RouterOS request. */
public record PlanChangeResponse(String action, String resourceType, String description) {
}
