package com.mikrotikmanager.domain;

import com.mikrotikmanager.gateway.ManagedResourceIdentifier;

import java.util.List;
import java.util.Optional;

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
        return filter != null
                && ManagedResourceIdentifier.isOwnedByDevice(filter.comment(), macAddress);
    }

    /** Exact desired-state check for the narrow rule shape created by this panel. */
    public static boolean isExactDesiredRule(RouterFirewallFilter filter, String macAddress) {
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
                && !filter.dynamic()
                && noAdditionalMatcher(filter);
    }

    /** Compatibility alias; callers should use the exact name for new code. */
    public static boolean isDesired(RouterFirewallFilter filter, String macAddress) {
        return isExactDesiredRule(filter, macAddress);
    }

    /** True only for a fully valid MTMGR device-block rule, for any managed MAC. */
    public static boolean isValidManagedDeviceBlockRule(RouterFirewallFilter filter) {
        return managedMacAddress(filter)
                .map(mac -> isExactDesiredRule(filter, mac))
                .orElse(false);
    }

    /** Extracts the MAC represented by an exact MTMGR ownership comment. */
    public static Optional<String> managedMacAddress(RouterFirewallFilter filter) {
        if (filter == null || filter.comment() == null || !filter.comment().matches("MTMGR:DEVICE:[0-9A-Fa-f]{2}(?:-[0-9A-Fa-f]{2}){5}")) {
            return Optional.empty();
        }
        String mac = filter.comment().substring("MTMGR:DEVICE:".length()).replace('-', ':');
        try {
            String normalized = ManagedResourceIdentifier.normalizeMac(mac);
            return ManagedResourceIdentifier.isOwnedByDevice(filter.comment(), normalized)
                    ? Optional.of(normalized) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * A safe forward prefix contains every exact MTMGR device-block rule before
     * the first static external forward rule. Relative managed-rule order is
     * deliberately irrelevant.
     */
    public static boolean hasSafeForwardPrefix(List<RouterFirewallFilter> filters) {
        int firstExternal = firstExternalStaticForwardPosition(filters);
        if (firstExternal < 0) {
            return true;
        }
        for (int index = firstExternal + 1; index < filters.size(); index++) {
            if (isStaticForward(filters.get(index)) && isValidManagedDeviceBlockRule(filters.get(index))) {
                return false;
            }
        }
        return true;
    }

    public static int firstExternalStaticForwardPosition(List<RouterFirewallFilter> filters) {
        for (int index = 0; index < filters.size(); index++) {
            RouterFirewallFilter filter = filters.get(index);
            if (isStaticForward(filter) && !isValidManagedDeviceBlockRule(filter)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isStaticForward(RouterFirewallFilter filter) {
        return filter != null && !filter.dynamic() && "forward".equalsIgnoreCase(filter.chain());
    }

    private static boolean noAdditionalMatcher(RouterFirewallFilter filter) {
        return blank(filter.protocol()) && blank(filter.srcAddress()) && blank(filter.srcAddressList())
                && blank(filter.dstAddress()) && blank(filter.dstAddressList()) && blank(filter.srcPort())
                && blank(filter.dstPort()) && blank(filter.inInterface()) && blank(filter.inInterfaceList())
                && blank(filter.outInterface()) && blank(filter.outInterfaceList()) && blank(filter.connectionState())
                && blank(filter.connectionMark()) && blank(filter.packetMark()) && blank(filter.routingMark())
                && blank(filter.layer7Protocol()) && blank(filter.tcpFlags()) && blank(filter.icmpOptions())
                && blank(filter.addressType()) && blank(filter.connectionNatState())
                && !filter.unknownRestrictiveMatcher();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
