package com.mikrotikmanager.gateway.routeros.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** RouterOS transport DTO for the match fields relevant to the one MTMGR rule. */
public final class RouterOsFirewallFilterDto {
    private static final Set<String> NON_RESTRICTIVE_UNKNOWN_FIELDS = Set.of(
            "bytes", "packets", "creation-time", "last-seen", "invalid", "active", "default",
            "log", "log-prefix", "limit", "burst-limit", "burst-threshold", "burst-time",
            "time", "comment", "disabled", "dynamic", "chain", "action", ".id");

    @JsonProperty(".id") private String id;
    @JsonProperty("action") private String action;
    @JsonProperty("disabled") private String disabled;
    @JsonProperty("dynamic") private String dynamic;
    @JsonProperty("chain") private String chain;
    @JsonProperty("comment") private String comment;
    @JsonProperty("src-address") private String srcAddress;
    @JsonProperty("src-address-list") private String srcAddressList;
    @JsonProperty("src-mac-address") private String srcMacAddress;
    @JsonProperty("protocol") private String protocol;
    @JsonProperty("dst-address") private String dstAddress;
    @JsonProperty("dst-address-list") private String dstAddressList;
    @JsonProperty("src-port") private String srcPort;
    @JsonProperty("dst-port") private String dstPort;
    @JsonProperty("in-interface") private String inInterface;
    @JsonProperty("in-interface-list") private String inInterfaceList;
    @JsonProperty("out-interface") private String outInterface;
    @JsonProperty("out-interface-list") private String outInterfaceList;
    @JsonProperty("connection-state") private String connectionState;
    @JsonProperty("connection-mark") private String connectionMark;
    @JsonProperty("packet-mark") private String packetMark;
    @JsonProperty("routing-mark") private String routingMark;
    @JsonProperty("layer7-protocol") private String layer7Protocol;
    @JsonProperty("tcp-flags") private String tcpFlags;
    @JsonProperty("icmp-options") private String icmpOptions;
    @JsonProperty("address-type") private String addressType;
    @JsonProperty("connection-nat-state") private String connectionNatState;
    @JsonIgnore private final Map<String, JsonNode> unknownFields = new LinkedHashMap<>();

    public RouterOsFirewallFilterDto() { }

    public RouterOsFirewallFilterDto(String id, String action, String disabled, String dynamic, String chain, String comment) {
        this(id, action, disabled, dynamic, chain, comment, null, null, null);
    }

    public RouterOsFirewallFilterDto(String id, String action, String disabled, String dynamic, String chain, String comment,
                                     String srcAddress, String srcAddressList) {
        this(id, action, disabled, dynamic, chain, comment, srcAddress, srcAddressList, null);
    }

    public RouterOsFirewallFilterDto(String id, String action, String disabled, String dynamic, String chain, String comment,
                                     String srcAddress, String srcAddressList, String srcMacAddress) {
        this.id = id; this.action = action; this.disabled = disabled; this.dynamic = dynamic; this.chain = chain; this.comment = comment;
        this.srcAddress = srcAddress; this.srcAddressList = srcAddressList; this.srcMacAddress = srcMacAddress;
    }

    @JsonAnySetter
    void unknown(String name, JsonNode value) { unknownFields.put(name, value); }

    public boolean hasUnknownRestrictiveMatcher() {
        return unknownFields.entrySet().stream().anyMatch(entry -> !NON_RESTRICTIVE_UNKNOWN_FIELDS.contains(entry.getKey())
                && entry.getValue() != null && !entry.getValue().isNull() && !(entry.getValue().isTextual() && entry.getValue().asText().isBlank()));
    }
    public String id() { return id; } public String action() { return action; } public String disabled() { return disabled; }
    public String dynamic() { return dynamic; } public String chain() { return chain; } public String comment() { return comment; }
    public String srcAddress() { return srcAddress; } public String srcAddressList() { return srcAddressList; } public String srcMacAddress() { return srcMacAddress; }
    public String protocol() { return protocol; } public String dstAddress() { return dstAddress; } public String dstAddressList() { return dstAddressList; }
    public String srcPort() { return srcPort; } public String dstPort() { return dstPort; } public String inInterface() { return inInterface; }
    public String inInterfaceList() { return inInterfaceList; } public String outInterface() { return outInterface; } public String outInterfaceList() { return outInterfaceList; }
    public String connectionState() { return connectionState; } public String connectionMark() { return connectionMark; } public String packetMark() { return packetMark; }
    public String routingMark() { return routingMark; } public String layer7Protocol() { return layer7Protocol; } public String tcpFlags() { return tcpFlags; }
    public String icmpOptions() { return icmpOptions; } public String addressType() { return addressType; } public String connectionNatState() { return connectionNatState; }
}
