package com.mikrotikmanager.api.dto;

public record DiagnosticCheckResponse(String name, boolean available, String detail) {
}
