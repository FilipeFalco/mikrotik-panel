package com.mikrotikmanager.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SpeedLimitRequest(
        @NotNull(message = "Informe o limite de download.")
        @PositiveOrZero(message = "O limite de download não pode ser negativo.")
        @Max(value = 10_000_000_000L, message = "O limite de download é maior que o permitido.")
        Long downloadBps,
        @NotNull(message = "Informe o limite de upload.")
        @PositiveOrZero(message = "O limite de upload não pode ser negativo.")
        @Max(value = 10_000_000_000L, message = "O limite de upload é maior que o permitido.")
        Long uploadBps
) {
}
