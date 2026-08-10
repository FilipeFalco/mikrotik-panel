package com.mikrotikmanager.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Untrusted intent to analyse a future port speed change. */
public record PortSpeedPlanRequest(
        @NotBlank(message = "Informe a interface da porta.")
        @Size(max = 128, message = "O nome da interface é maior que o permitido.")
        String interfaceName,
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
