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
        String invalid,
        String target,
        String parent,
        @JsonProperty("limit-at") String limitAt,
        String priority,
        String queue,
        @JsonProperty("burst-limit") String burstLimit,
        @JsonProperty("burst-threshold") String burstThreshold,
        @JsonProperty("burst-time") String burstTime,
        @JsonProperty("bucket-size") String bucketSize,
        String time,
        @JsonProperty("packet-marks") String packetMarks,
        @JsonProperty("dst-address") String dstAddress
) {
    public RouterOsSimpleQueueDto(String id, String name, String comment, String maxLimit, String disabled,
                                  String dynamic, String target) {
        this(id, name, comment, maxLimit, disabled, dynamic, null, target, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
