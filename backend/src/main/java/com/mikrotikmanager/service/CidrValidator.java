package com.mikrotikmanager.service;

import java.util.ArrayList;
import java.util.List;

/**
 * CIDR parser that accepts IP literals only. It intentionally does not call the
 * network stack, so a hostname can never trigger DNS during validation.
 */
final class CidrValidator {
    private CidrValidator() {
    }

    static boolean isValid(String cidr) {
        return cidr == null || cidr.isBlank() || parse(cidr) != null;
    }

    /**
     * Returns the canonical network address for a valid CIDR. Empty values are
     * preserved as an absent network and invalid values are rejected explicitly.
     */
    static String normalize(String cidr) {
        if (cidr == null) {
            return null;
        }
        if (cidr.isBlank()) {
            return "";
        }

        ParsedCidr parsed = parse(cidr);
        if (parsed == null) {
            throw new IllegalArgumentException("A rede deve estar no formato CIDR com um endereço IP literal.");
        }

        byte[] network = parsed.address().clone();
        applyNetworkMask(network, parsed.prefixLength());
        String address = network.length == 4 ? formatIpv4(network) : formatIpv6(network);
        return address + "/" + parsed.prefixLength();
    }

    private static ParsedCidr parse(String cidr) {
        String[] parts = cidr.trim().split("/", -1);
        if (parts.length != 2 || parts[0].isEmpty() || !parts[1].matches("\\d{1,3}")) {
            return null;
        }

        byte[] address = parseIpLiteral(parts[0]);
        if (address == null) {
            return null;
        }

        int prefixLength;
        try {
            prefixLength = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            return null;
        }
        int maximum = address.length * Byte.SIZE;
        if (prefixLength < 0 || prefixLength > maximum) {
            return null;
        }
        return new ParsedCidr(address, prefixLength);
    }

    private static byte[] parseIpLiteral(String address) {
        if (address.indexOf(':') >= 0) {
            return parseIpv6(address);
        }
        return parseIpv4(address);
    }

    private static byte[] parseIpv4(String address) {
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }

        byte[] result = new byte[4];
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (!octet.matches("\\d{1,3}")) {
                return null;
            }
            try {
                int value = Integer.parseInt(octet);
                if (value > 255) {
                    return null;
                }
                result[index] = (byte) value;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return result;
    }

    private static byte[] parseIpv6(String address) {
        if (address.isEmpty() || !address.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }

        int compression = address.indexOf("::");
        if (compression != address.lastIndexOf("::")) {
            return null;
        }

        String left = compression >= 0 ? address.substring(0, compression) : address;
        String right = compression >= 0 ? address.substring(compression + 2) : "";
        List<String> leftParts = splitIpv6Side(left);
        List<String> rightParts = splitIpv6Side(right);
        if (leftParts == null || rightParts == null) {
            return null;
        }

        List<String> allParts = new ArrayList<>(leftParts.size() + rightParts.size());
        allParts.addAll(leftParts);
        allParts.addAll(rightParts);
        for (int index = 0; index < allParts.size(); index++) {
            boolean ipv4MustBeFinalTextualPart = index == allParts.size() - 1
                    && (compression < 0 || !rightParts.isEmpty());
            if (allParts.get(index).contains(".") && !ipv4MustBeFinalTextualPart) {
                return null;
            }
        }

        List<Integer> leftGroups = parseIpv6Groups(leftParts);
        List<Integer> rightGroups = parseIpv6Groups(rightParts);
        if (leftGroups == null || rightGroups == null) {
            return null;
        }

        int groupCount = leftGroups.size() + rightGroups.size();
        if (compression >= 0 ? groupCount >= 8 : groupCount != 8) {
            return null;
        }

        int zeroGroups = compression >= 0 ? 8 - groupCount : 0;
        byte[] result = new byte[16];
        int index = 0;
        for (int group : leftGroups) {
            writeGroup(result, index++, group);
        }
        index += zeroGroups;
        for (int group : rightGroups) {
            writeGroup(result, index++, group);
        }
        return result;
    }

    private static List<String> splitIpv6Side(String side) {
        if (side.isEmpty()) {
            return List.of();
        }
        String[] groups = side.split(":", -1);
        for (String group : groups) {
            if (group.isEmpty()) {
                return null;
            }
        }
        return List.of(groups);
    }

    private static List<Integer> parseIpv6Groups(List<String> parts) {
        List<Integer> groups = new ArrayList<>();
        for (String part : parts) {
            if (part.contains(".")) {
                byte[] ipv4 = parseIpv4(part);
                if (ipv4 == null) {
                    return null;
                }
                groups.add(unsigned(ipv4[0]) << 8 | unsigned(ipv4[1]));
                groups.add(unsigned(ipv4[2]) << 8 | unsigned(ipv4[3]));
            } else if (part.matches("[0-9A-Fa-f]{1,4}")) {
                groups.add(Integer.parseInt(part, 16));
            } else {
                return null;
            }
        }
        return groups;
    }

    private static void applyNetworkMask(byte[] address, int prefixLength) {
        int completeBytes = prefixLength / Byte.SIZE;
        int remainingBits = prefixLength % Byte.SIZE;
        if (remainingBits > 0) {
            address[completeBytes] &= (byte) (0xFF << (Byte.SIZE - remainingBits));
            completeBytes++;
        }
        for (int index = completeBytes; index < address.length; index++) {
            address[index] = 0;
        }
    }

    private static String formatIpv4(byte[] address) {
        return unsigned(address[0]) + "." + unsigned(address[1]) + "." + unsigned(address[2]) + "." + unsigned(address[3]);
    }

    private static String formatIpv6(byte[] address) {
        int[] groups = new int[8];
        for (int index = 0; index < groups.length; index++) {
            groups[index] = unsigned(address[index * 2]) << 8 | unsigned(address[index * 2 + 1]);
        }

        int longestStart = -1;
        int longestLength = 0;
        for (int index = 0; index < groups.length; ) {
            if (groups[index] != 0) {
                index++;
                continue;
            }
            int start = index;
            while (index < groups.length && groups[index] == 0) {
                index++;
            }
            int length = index - start;
            if (length >= 2 && length > longestLength) {
                longestStart = start;
                longestLength = length;
            }
        }

        StringBuilder result = new StringBuilder();
        for (int index = 0; index < groups.length; index++) {
            if (index == longestStart) {
                result.append("::");
                index += longestLength - 1;
                continue;
            }
            if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') {
                result.append(':');
            }
            result.append(Integer.toHexString(groups[index]));
        }
        return result.toString();
    }

    private static void writeGroup(byte[] target, int index, int group) {
        target[index * 2] = (byte) (group >>> Byte.SIZE);
        target[index * 2 + 1] = (byte) group;
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private record ParsedCidr(byte[] address, int prefixLength) {
    }
}
