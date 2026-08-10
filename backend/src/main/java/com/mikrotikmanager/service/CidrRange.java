package com.mikrotikmanager.service;

import java.util.Arrays;
import java.util.Objects;

/**
 * Deterministic IPv4/IPv6 range comparison used by reconciliation conflict
 * detection. Ranges are stored as the masked network address and the broadcast
 * (all-host-bits-set) address, both derived without {@link java.net.InetAddress}
 * so a hostname can never trigger DNS during overlap evaluation.
 *
 * <p>Two ranges overlap when their inclusive IP intervals intersect. This is a
 * pure integer interval test and does not imply ownership: a foreign queue
 * whose target overlaps a managed network is a {@code FOREIGN} conflict, never
 * a {@code MANAGED} resource.</p>
 */
record CidrRange(byte[] network, byte[] broadcast, int prefixLength) {
    CidrRange {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(broadcast, "broadcast");
        if (network.length != broadcast.length) {
            throw new IllegalArgumentException("network and broadcast must share the same address length");
        }
        network = network.clone();
        broadcast = broadcast.clone();
    }

    boolean overlaps(CidrRange other) {
        if (other == null || other.network.length != network.length) {
            return false;
        }
        return compareUnsigned(broadcast, other.network) >= 0
                && compareUnsigned(other.broadcast, network) >= 0;
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int index = 0; index < left.length; index++) {
            int a = Byte.toUnsignedInt(left[index]);
            int b = Byte.toUnsignedInt(right[index]);
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CidrRange range
                && prefixLength == range.prefixLength
                && Arrays.equals(network, range.network)
                && Arrays.equals(broadcast, range.broadcast);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(network) * 31 + Arrays.hashCode(broadcast) + prefixLength;
    }

    @Override
    public String toString() {
        return "CidrRange[" + format(network) + "/" + prefixLength + ".." + format(broadcast) + "]";
    }

    private static String format(byte[] address) {
        if (address.length == 4) {
            return Byte.toUnsignedInt(address[0]) + "." + Byte.toUnsignedInt(address[1]) + "."
                    + Byte.toUnsignedInt(address[2]) + "." + Byte.toUnsignedInt(address[3]);
        }
        return "<ipv6>";
    }
}
