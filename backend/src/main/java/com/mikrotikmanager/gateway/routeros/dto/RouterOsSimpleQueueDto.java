package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RouterOS representation returned by {@code GET /rest/queue/simple}.
 *
 * <p>RouterOS REST serializes all values as strings. Keeping this DTO string
 * based prevents RouterOS transport details from escaping the gateway layer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterOsSimpleQueueDto(
        @JsonProperty(".id") String id,
        String name,
        String comment,
        @JsonProperty("max-limit") String maxLimit,
        String disabled,
        String dynamic,
        String target
) {
}
