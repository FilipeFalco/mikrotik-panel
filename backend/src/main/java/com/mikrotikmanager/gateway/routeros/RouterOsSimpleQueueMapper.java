package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSimpleQueueDto;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps only explicitly application-owned RouterOS Simple Queues.
 *
 * <p>A queue is never adopted merely because it exists or its name looks
 * familiar. It must have the exact {@code MTMGR:PORT:<interface>} comment
 * verified by {@link ManagedResourceIdentifier#isOwnedByPort(String, String)}.
 * Disabled, missing, malformed or overflowing limits are safely excluded: a
 * read-only view must not claim a speed limit it cannot establish reliably.
 */
public final class RouterOsSimpleQueueMapper {
    private static final String PORT_COMMENT_PREFIX = "MTMGR:PORT:";

    private RouterOsSimpleQueueMapper() {
    }

    /**
     * Returns interface limits represented by enabled, application-owned
     * Simple Queues. Manual queues are intentionally omitted.
     */
    public static Map<String, SpeedLimit> toOwnedPortSpeeds(List<RouterOsSimpleQueueDto> queues) {
        if (queues == null || queues.isEmpty()) {
            return Map.of();
        }

        Map<String, SpeedLimit> portSpeeds = new LinkedHashMap<>();
        Set<String> ambiguousPorts = new HashSet<>();
        for (RouterOsSimpleQueueDto queue : queues) {
            if (queue == null || isDisabledOrUntrustworthy(queue.disabled())) {
                continue;
            }

            String interfaceName = exactOwnedPort(queue.comment());
            if (interfaceName == null) {
                continue;
            }

            Optional<SpeedLimit> speedLimit = RouterOsRateParser.parseSimpleQueueMaxLimit(queue.maxLimit());
            if (speedLimit.isEmpty()) {
                continue;
            }

            // Two exact comments for the same port are unsafe even for a
            // read-only presentation. Do not pick a RouterOS list entry; the
            // reconciliation report will expose this as ambiguous ownership.
            if (ambiguousPorts.contains(interfaceName)) {
                continue;
            }
            if (portSpeeds.containsKey(interfaceName)) {
                portSpeeds.remove(interfaceName);
                ambiguousPorts.add(interfaceName);
                continue;
            }
            portSpeeds.put(interfaceName, speedLimit.get());
        }
        return Map.copyOf(portSpeeds);
    }

    /** Resolves DEVICE queues from one collection response; never performs an N+1 lookup. */
    public static Map<String, SpeedLimit> toOwnedDeviceSpeeds(List<RouterOsSimpleQueueDto> queues) {
        if (queues == null) return Map.of();
        Map<String, SpeedLimit> result = new LinkedHashMap<>(); Set<String> ambiguous = new HashSet<>();
        for (RouterOsSimpleQueueDto queue : queues) {
            if (queue == null || isDisabledOrUntrustworthy(queue.disabled()) || isDisabledOrUntrustworthy(queue.dynamic())) continue;
            String mac = exactOwnedDevice(queue.comment());
            if (mac == null) continue;
            Optional<SpeedLimit> limit = RouterOsRateParser.parseSimpleQueueMaxLimit(queue.maxLimit());
            if (limit.isEmpty() || ambiguous.contains(mac)) continue;
            if (result.putIfAbsent(mac, limit.get()) != null) { result.remove(mac); ambiguous.add(mac); }
        }
        return Map.copyOf(result);
    }

    private static boolean isDisabledOrUntrustworthy(String rawDisabled) {
        try {
            return RouterOsValueParser.booleanOrDefault(rawDisabled, false, "queue/simple.disabled");
        } catch (IllegalArgumentException exception) {
            // An unexpected boolean must not make a queue look active.
            return true;
        }
    }

    /**
     * A Phase 1 port queue targets the managed CIDR, so {@code target} is not
     * an ownership signal and is deliberately not used here. The interface is
     * encoded only in the exact application comment.
     */
    private static String exactOwnedPort(String comment) {
        if (comment == null || !comment.startsWith(PORT_COMMENT_PREFIX)) {
            return null;
        }
        String interfaceName = comment.substring(PORT_COMMENT_PREFIX.length());
        return ManagedResourceIdentifier.isOwnedByPort(comment, interfaceName) ? interfaceName : null;
    }

    private static String exactOwnedDevice(String comment) {
        if (comment == null || !comment.startsWith("MTMGR:DEVICE:")) return null;
        String suffix = comment.substring("MTMGR:DEVICE:".length());
        String mac = suffix.replace('-', ':');
        return ManagedResourceIdentifier.isOwnedByDevice(comment, mac) ? ManagedResourceIdentifier.normalizeMac(mac) : null;
    }
}
