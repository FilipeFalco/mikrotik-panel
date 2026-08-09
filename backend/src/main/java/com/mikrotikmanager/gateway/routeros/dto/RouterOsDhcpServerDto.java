package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RouterOS representation returned by {@code GET /rest/ip/dhcp-server}.
 *
 * <p>RouterOS REST serializes values as strings. The fields deliberately stay
 * optional at the transport boundary because a RouterOS installation can omit
 * properties that are irrelevant to a particular DHCP server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterOsDhcpServerDto(
        @JsonProperty(".id") String id,
        String name,
        @JsonProperty("interface") String interfaceName,
        String disabled
) {
}
