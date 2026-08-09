package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSimpleQueueDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

            // Duplicate exact ownership markers are ambiguous; retain the
            // first RouterOS list entry rather than silently overwriting it.
            portSpeeds.putIfAbsent(interfaceName, speedLimit.get());
        }
        return Map.copyOf(portSpeeds);
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
}
