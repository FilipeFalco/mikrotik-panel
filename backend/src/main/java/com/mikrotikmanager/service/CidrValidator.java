package com.mikrotikmanager.service;

import java.net.InetAddress;
import java.net.UnknownHostException;

final class CidrValidator {
    private CidrValidator() {
    }

    static boolean isValid(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return true;
        }
        String[] parts = cidr.trim().split("/", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            int maximum = address.getAddress().length == 4 ? 32 : 128;
            return prefix >= 0 && prefix <= maximum;
        } catch (UnknownHostException | NumberFormatException ignored) {
            return false;
        }
    }
}
