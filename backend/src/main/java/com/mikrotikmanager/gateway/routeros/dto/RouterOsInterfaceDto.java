package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * RouterOS representation returned by {@code GET /rest/interface}.
 *
 * <p>All values are kept as strings because that is the RouterOS REST
 * response format. No raw JSON representation leaves this package.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterOsInterfaceDto(
        String name,
        String type,
        String running,
        String disabled
) {
}
