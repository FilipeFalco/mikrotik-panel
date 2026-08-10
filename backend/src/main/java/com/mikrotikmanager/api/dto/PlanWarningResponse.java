package com.mikrotikmanager.api.dto;

/** A safe warning returned from a dry-run analysis. */
public record PlanWarningResponse(String code, String description, String severity) {
}
