package com.mikrotikmanager.domain;

import com.mikrotikmanager.gateway.ManagedResourceIdentifier;

import java.util.Objects;

/**
 * Immutable, application-built shape of the one RouterOS rule owned by the
 * panel for a device. It is intentionally not a general firewall-rule DTO.
 */
public record ManagedDeviceBlockRule(String macAddress) {
    public ManagedDeviceBlockRule {
        macAddress = ManagedResourceIdentifier.normalizeMac(macAddress);
    }

    public String chain() {
        return "forward";
    }

    public String action() {
        return "drop";
    }

    public String srcMacAddress() {
        return macAddress;
    }

    public String comment() {
        return ManagedResourceIdentifier.expectedDeviceComment(macAddress);
    }

    public boolean disabled() {
        return false;
    }

    public boolean dynamic() {
        return false;
    }

    public static boolean isOwned(RouterFirewallFilter filter, String macAddress) {
        return filter != null && !filter.dynamic()
                && ManagedResourceIdentifier.isOwnedByDevice(filter.comment(), macAddress);
    }

    public static boolean isDesired(RouterFirewallFilter filter, String macAddress) {
        if (!isOwned(filter, macAddress)) {
            return false;
        }
        String normalized;
        try {
            normalized = ManagedResourceIdentifier.normalizeMac(macAddress);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return "forward".equalsIgnoreCase(filter.chain())
                && "drop".equalsIgnoreCase(filter.action())
                && normalized.equalsIgnoreCase(filter.srcMacAddress())
                && !filter.disabled()
                && !filter.dynamic();
    }
}
