package com.mikrotikmanager.api.dto;

/** Concise safe explanation attached to one reconciliation resource. */
public record ReconciliationFindingResponse(String code, String description, String severity) {
}
