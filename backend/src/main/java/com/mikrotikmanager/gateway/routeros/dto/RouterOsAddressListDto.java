package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Read-only representation returned by {@code GET /rest/ip/firewall/address-list}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterOsAddressListDto(
        @JsonProperty(".id") String id,
        @JsonProperty("list") String listName,
        String address,
        String comment,
        String disabled,
        String dynamic
) {
}
