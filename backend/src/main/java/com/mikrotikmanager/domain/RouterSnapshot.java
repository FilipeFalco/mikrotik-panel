package com.mikrotikmanager.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable, read-only RouterOS observation used by one reconciliation or
 * operation-plan run. It deliberately has no reference back to a gateway or
 * HTTP client.
 */
public record RouterSnapshot(
        Instant capturedAt,
        List<RouterInterface> interfaces,
        List<RouterDhcpServer> dhcpServers,
        List<RouterDhcpLease> dhcpLeases,
        List<RouterDevice> devices,
        List<RouterSimpleQueue> simpleQueues,
        List<RouterFirewallFilter> firewallFilters,
        List<RouterAddressListEntry> addressListEntries
) {
    public RouterSnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        interfaces = List.copyOf(interfaces);
        dhcpServers = List.copyOf(dhcpServers);
        dhcpLeases = List.copyOf(dhcpLeases);
        devices = List.copyOf(devices);
        simpleQueues = List.copyOf(simpleQueues);
        firewallFilters = List.copyOf(firewallFilters);
        addressListEntries = List.copyOf(addressListEntries);
    }

    public boolean fastTrackDetected() {
        return firewallFilters.stream().anyMatch(RouterFirewallFilter::isActiveFastTrack);
    }

    /**
     * A diagnostic version marker, not an authorization token. It intentionally
     * excludes the capture time and contains only a digest, never raw RouterOS
     * content or credentials.
     */
    public String fingerprint() {
        String material = collection("interfaces", interfaces.stream()
                        .map(value -> fields(value.name(), value.type(), value.running(), value.disabled())))
                + collection("dhcpServers", dhcpServers.stream()
                        .map(value -> fields(value.name(), value.interfaceName(), value.disabled())))
                + collection("dhcpLeases", dhcpLeases.stream()
                        .map(value -> fields(value.macAddress(), value.ipAddress(), value.server(), value.interfaceName(),
                                value.status(), value.blockAccess(), value.comment(), value.dynamic(), value.disabled())))
                + collection("devices", devices.stream()
                        .map(value -> fields(value.macAddress(), value.ipAddress(), value.interfaceName(), value.status(),
                                value.blocked(), value.leaseComment(), value.speedLimit())))
                + collection("simpleQueues", simpleQueues.stream()
                        .map(value -> fields(value.name(), value.comment(), value.target(), value.maxLimit(), value.disabled(),
                                value.dynamic())))
                + collection("firewallFilters", firewallFilters.stream()
                        .map(value -> fields(value.action(), value.chain(), value.comment(), value.disabled(), value.dynamic(),
                                value.srcAddress(), value.srcAddressList(), value.srcMacAddress())))
                + collection("addressListEntries", addressListEntries.stream()
                        .map(value -> fields(value.listName(), value.address(), value.comment(), value.disabled(), value.dynamic())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the JVM.", exception);
        }
    }

    private static String collection(String name, java.util.stream.Stream<String> values) {
        return name + "[" + values.sorted().collect(Collectors.joining(",")) + "]";
    }

    /** Length-prefix values so a comment or name cannot blur field boundaries. */
    private static String fields(Object... values) {
        return Arrays.stream(values)
                .map(value -> String.valueOf(value))
                .map(value -> value.length() + ":" + value)
                .collect(Collectors.joining("|"));
    }
}
