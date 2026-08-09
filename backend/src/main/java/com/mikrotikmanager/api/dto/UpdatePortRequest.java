package com.mikrotikmanager.api.dto;

import com.mikrotikmanager.domain.ManagedPortRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePortRequest(
        @NotBlank(message = "Informe um nome amigável para a porta.")
        @Size(max = 100, message = "O nome amigável deve ter no máximo 100 caracteres.")
        String friendlyName,
        @Size(max = 500, message = "A descrição deve ter no máximo 500 caracteres.")
        String description,
        @Size(max = 64, message = "A rede deve ter no máximo 64 caracteres.")
        String network,
        @Size(max = 100, message = "O servidor DHCP deve ter no máximo 100 caracteres.")
        String dhcpServer,
        boolean enabled,
        ManagedPortRole role
) {
}
