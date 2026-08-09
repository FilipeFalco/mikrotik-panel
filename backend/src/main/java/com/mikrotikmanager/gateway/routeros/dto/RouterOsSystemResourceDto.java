package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RouterOS representation returned by {@code GET /rest/system/resource}.
 *
 * <p>RouterOS REST encodes property values as strings, including values that
 * represent numbers. This DTO intentionally preserves that transport shape;
 * conversion belongs to {@code RouterOsValueParser} at the gateway boundary.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterOsSystemResourceDto(
        String version,
        String uptime,
        @JsonProperty("cpu-load") String cpuLoad,
        @JsonProperty("architecture-name") String architectureName,
        @JsonProperty("board-name") String boardName,
        String platform
) {
}
