package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RouterOS representation returned by {@code GET /rest/ip/dhcp-server/lease}.
 *
 * <p>All fields intentionally retain the RouterOS REST string representation.
 * Conversion into application domain models is confined to the RouterOS
 * gateway layer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterOsDhcpLeaseDto(
        @JsonProperty(".id") String id,
        String address,
        @JsonProperty("mac-address") String macAddress,
        @JsonProperty("host-name") String hostName,
        String server,
        String status,
        @JsonProperty("block-access") String blockAccess,
        String comment,
        @JsonProperty("last-seen") String lastSeen,
        @JsonProperty("rate-limit") String rateLimit,
        String dynamic,
        String disabled
) {
}
