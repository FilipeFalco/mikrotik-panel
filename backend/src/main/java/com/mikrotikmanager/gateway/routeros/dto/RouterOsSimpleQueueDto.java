package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** RouterOS /queue/simple transport state. Unknown fields are retained for fail-closed semantic checks. */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class RouterOsSimpleQueueDto {
    @JsonProperty(".id") private String id;
    private String name, comment, disabled, dynamic, invalid, target, parent, priority, queue, time;
    @JsonProperty("max-limit") private String maxLimit;
    @JsonProperty("limit-at") private String limitAt;
    @JsonProperty("burst-limit") private String burstLimit;
    @JsonProperty("burst-threshold") private String burstThreshold;
    @JsonProperty("burst-time") private String burstTime;
    @JsonProperty("bucket-size") private String bucketSize;
    @JsonProperty("packet-marks") private String packetMarks;
    @JsonProperty("dst-address") private String dstAddress;
    @JsonProperty("total-limit-at") private String totalLimitAt;
    @JsonProperty("total-max-limit") private String totalMaxLimit;
    @JsonProperty("total-priority") private String totalPriority;
    @JsonProperty("total-queue") private String totalQueue;
    @JsonProperty("total-burst-limit") private String totalBurstLimit;
    @JsonProperty("total-burst-threshold") private String totalBurstThreshold;
    @JsonProperty("total-burst-time") private String totalBurstTime;
    @JsonProperty("total-bucket-size") private String totalBucketSize;
    private final Map<String, JsonNode> unknownFields = new LinkedHashMap<>();

    public RouterOsSimpleQueueDto() { }
    public RouterOsSimpleQueueDto(String id, String name, String comment, String maxLimit, String disabled, String dynamic, String target) {
        this.id=id; this.name=name; this.comment=comment; this.maxLimit=maxLimit; this.disabled=disabled; this.dynamic=dynamic; this.target=target;
    }
    public RouterOsSimpleQueueDto(String id, String name, String comment, String maxLimit, String disabled, String dynamic,
                                  String invalid, String target, String parent, String limitAt, String priority, String queue,
                                  String burstLimit, String burstThreshold, String burstTime, String bucketSize, String time,
                                  String packetMarks, String dstAddress) {
        this(id, name, comment, maxLimit, disabled, dynamic, target);
        this.invalid=invalid; this.parent=parent; this.limitAt=limitAt; this.priority=priority; this.queue=queue;
        this.burstLimit=burstLimit; this.burstThreshold=burstThreshold; this.burstTime=burstTime; this.bucketSize=bucketSize;
        this.time=time; this.packetMarks=packetMarks; this.dstAddress=dstAddress;
    }
    @JsonAnySetter public void unknown(String property, JsonNode value) { unknownFields.put(property, value); }
    public String id(){return id;} public String name(){return name;} public String comment(){return comment;} public String maxLimit(){return maxLimit;}
    public String disabled(){return disabled;} public String dynamic(){return dynamic;} public String invalid(){return invalid;} public String target(){return target;} public String parent(){return parent;}
    public String limitAt(){return limitAt;} public String priority(){return priority;} public String queue(){return queue;} public String burstLimit(){return burstLimit;} public String burstThreshold(){return burstThreshold;} public String burstTime(){return burstTime;} public String bucketSize(){return bucketSize;} public String time(){return time;} public String packetMarks(){return packetMarks;} public String dstAddress(){return dstAddress;}
    public String totalLimitAt(){return totalLimitAt;} public String totalMaxLimit(){return totalMaxLimit;} public String totalPriority(){return totalPriority;} public String totalQueue(){return totalQueue;} public String totalBurstLimit(){return totalBurstLimit;} public String totalBurstThreshold(){return totalBurstThreshold;} public String totalBurstTime(){return totalBurstTime;} public String totalBucketSize(){return totalBucketSize;}
    public Map<String, JsonNode> unknownFields(){return Map.copyOf(unknownFields);}
}
