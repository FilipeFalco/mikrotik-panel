package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.ResourceOwnership;

import java.util.Locale;

/**
 * Central contract for comments that may identify resources created by this
 * application in a future write phase.
 *
 * <p>A {@code MTMGR:} prefix alone never proves ownership. Callers must compare
 * the complete, expected comment for the resource they are evaluating.</p>
 */
public final class ManagedResourceIdentifier {
    private static final String PREFIX = "MTMGR";
    private static final String DEVICE_PREFIX = PREFIX + ":DEVICE:";
    private static final String PORT_PREFIX = PREFIX + ":PORT:";

    private ManagedResourceIdentifier() {
    }

    /**
     * Builds the exact ownership comment expected for a device resource.
     */
    public static String expectedDeviceComment(String macAddress) {
        return DEVICE_PREFIX + normalizeMac(macAddress).replace(':', '-');
    }

    /**
     * Builds the exact ownership comment expected for a port resource.
     *
     * <p>Interface names are intentionally not trimmed or otherwise normalized:
     * ownership is an exact contract with the local managed-port identity.</p>
     */
    public static String expectedPortComment(String interfaceName) {
        return PORT_PREFIX + requireInterfaceName(interfaceName);
    }

    /**
     * Returns the future conventional queue name for a port. This aids
     * collision detection only: it is never ownership evidence.
     */
    public static String expectedPortQueueName(String interfaceName) {
        String safeName = requireInterfaceName(interfaceName).replaceAll("[^A-Za-z0-9._-]", "-");
        return "mtmgr-port-" + safeName;
    }

    /** Deterministic name only; the exact comment remains the ownership proof. */
    public static String expectedDeviceQueueName(String macAddress) {
        return "mtmgr-device-" + normalizeMac(macAddress).replace(":", "");
    }

    /**
     * Compatibility alias for {@link #expectedDeviceComment(String)}.
     */
    public static String forDevice(String macAddress) {
        return expectedDeviceComment(macAddress);
    }

    /**
     * Compatibility alias for {@link #expectedPortComment(String)}.
     */
    public static String forPort(String interfaceName) {
        return expectedPortComment(interfaceName);
    }

    /**
     * Returns whether the comment belongs to exactly this device. A generic MTMGR
     * prefix is deliberately insufficient for future destructive operations.
     */
    public static boolean isOwnedByDevice(String comment, String macAddress) {
        if (comment == null) {
            return false;
        }
        try {
            return comment.equals(expectedDeviceComment(macAddress));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Returns whether the comment belongs to exactly this port.
     */
    public static boolean isOwnedByPort(String comment, String interfaceName) {
        if (comment == null) {
            return false;
        }
        try {
            return comment.equals(expectedPortComment(interfaceName));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Classifies a device comment only in the context of one expected device.
     * A missing comment is unresolved rather than adopted; a non-empty,
     * non-exact comment is foreign to that device.
     */
    public static ResourceOwnership ownershipForDeviceComment(String comment, String macAddress) {
        if (!isValidMacAddress(macAddress)) {
            return ResourceOwnership.UNKNOWN;
        }
        return ownershipForExpectedComment(comment, expectedDeviceComment(macAddress));
    }

    /**
     * Classifies a port comment only in the context of one expected port.
     * A missing comment is unresolved rather than adopted; a non-empty,
     * non-exact comment is foreign to that port.
     */
    public static ResourceOwnership ownershipForPortComment(String comment, String interfaceName) {
        if (!isValidInterfaceName(interfaceName)) {
            return ResourceOwnership.UNKNOWN;
        }
        return ownershipForExpectedComment(comment, expectedPortComment(interfaceName));
    }

    public static String normalizeMac(String macAddress) {
        if (!isValidMacAddress(macAddress)) {
            throw new IllegalArgumentException("Endereço MAC inválido.");
        }
        return macAddress.toUpperCase(Locale.ROOT);
    }

    private static ResourceOwnership ownershipForExpectedComment(String comment, String expectedComment) {
        if (comment == null || comment.isBlank()) {
            return ResourceOwnership.UNKNOWN;
        }
        return comment.equals(expectedComment) ? ResourceOwnership.MANAGED : ResourceOwnership.FOREIGN;
    }

    private static String requireInterfaceName(String interfaceName) {
        if (!isValidInterfaceName(interfaceName)) {
            throw new IllegalArgumentException("Nome de interface inválido.");
        }
        return interfaceName;
    }

    private static boolean isValidMacAddress(String macAddress) {
        return macAddress != null && macAddress.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$");
    }

    private static boolean isValidInterfaceName(String interfaceName) {
        return interfaceName != null && !interfaceName.isBlank();
    }
}
