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
        String comment,
        @JsonProperty("src-address") String srcAddress,
        @JsonProperty("src-address-list") String srcAddressList,
        @JsonProperty("src-mac-address") String srcMacAddress
) {
    /** Compatibility constructor for existing read-only fixtures. */
    public RouterOsFirewallFilterDto(String id, String action, String disabled, String dynamic, String chain, String comment) {
        this(id, action, disabled, dynamic, chain, comment, null, null, null);
    }

    /** Compatibility constructor for the previous eight-field DTO shape. */
    public RouterOsFirewallFilterDto(String id, String action, String disabled, String dynamic, String chain, String comment,
                                     String srcAddress, String srcAddressList) {
        this(id, action, disabled, dynamic, chain, comment, srcAddress, srcAddressList, null);
    }
}
