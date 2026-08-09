package com.mikrotikmanager.gateway;

import java.util.Locale;

/** Central ownership marker for resources created by this application in later phases. */
public final class ManagedResourceIdentifier {
    private static final String PREFIX = "MTMGR";

    private ManagedResourceIdentifier() {
    }

    public static String forDevice(String macAddress) {
        return PREFIX + ":DEVICE:" + normalizeMac(macAddress).replace(':', '-');
    }

    public static String forPort(String interfaceName) {
        return PREFIX + ":PORT:" + interfaceName;
    }

    public static boolean isManagedComment(String comment) {
        return comment != null && comment.startsWith(PREFIX + ":");
    }

    public static String normalizeMac(String macAddress) {
        if (macAddress == null || !macAddress.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) {
            throw new IllegalArgumentException("Endereço MAC inválido.");
        }
        return macAddress.toUpperCase(Locale.ROOT);
    }
}
