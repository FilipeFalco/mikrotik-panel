package com.mikrotikmanager.api.dto;

import jakarta.validation.constraints.Size;

public record UpdateDeviceRequest(
        @Size(max = 100, message = "O nome amigável deve ter no máximo 100 caracteres.")
        String friendlyName,
        @Size(max = 500, message = "As observações devem ter no máximo 500 caracteres.")
        String notes
) {
}
