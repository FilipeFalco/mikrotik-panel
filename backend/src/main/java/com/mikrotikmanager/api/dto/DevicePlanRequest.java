package com.mikrotikmanager.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Untrusted device intent for a local dry-run endpoint.
 *
 * <p>The value identifies only what should be analysed. Ownership,
 * preconditions, and all RouterOS state are rebuilt by the backend.</p>
 */
public record DevicePlanRequest(
        @NotBlank(message = "Informe o endereço MAC do dispositivo.")
        @Size(max = 64, message = "O endereço MAC é maior que o permitido.")
        String macAddress
) {
}
