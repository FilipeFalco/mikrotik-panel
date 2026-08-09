package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RouterOS representation returned by {@code GET /rest/ip/firewall/filter}.
 *
 * <p>All fields remain strings because this is the documented RouterOS REST
 * JSON transport shape. Unknown future RouterOS properties are deliberately
 * ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterOsFirewallFilterDto(
        @JsonProperty(".id") String id,
        String action,
        String disabled,
        String dynamic,
        String chain,
        String comment
) {
}
