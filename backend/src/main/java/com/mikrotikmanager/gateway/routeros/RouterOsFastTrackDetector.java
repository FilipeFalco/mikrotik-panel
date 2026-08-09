package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.gateway.routeros.dto.RouterOsFirewallFilterDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only FastTrack detection based on RouterOS firewall filter records.
 *
 * <p>Only enabled {@code fasttrack-connection} actions are reported. A filter
 * with a missing {@code disabled} value uses RouterOS's enabled default; an
 * unparseable value is treated conservatively as not provably active.
 */
public final class RouterOsFastTrackDetector {
    private static final String FASTTRACK_ACTION = "fasttrack-connection";

    private RouterOsFastTrackDetector() {
    }

    public static boolean isDetected(List<RouterOsFirewallFilterDto> filters) {
        return !findActiveRules(filters).isEmpty();
    }

    /**
     * Finds active FastTrack rules and preserves minimal useful context for
     * diagnostics without exposing RouterOS transport objects.
     */
    public static List<RouterOsFastTrackRule> findActiveRules(List<RouterOsFirewallFilterDto> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }

        List<RouterOsFastTrackRule> activeRules = new ArrayList<>();
        for (RouterOsFirewallFilterDto filter : filters) {
            if (filter == null || !FASTTRACK_ACTION.equals(filter.action()) || isDisabledOrUntrustworthy(filter.disabled())) {
                continue;
            }
            activeRules.add(new RouterOsFastTrackRule(
                    RouterOsValueParser.optionalText(filter.chain()),
                    RouterOsValueParser.optionalText(filter.comment())));
        }
        return List.copyOf(activeRules);
    }

    private static boolean isDisabledOrUntrustworthy(String rawDisabled) {
        try {
            return RouterOsValueParser.booleanOrDefault(rawDisabled, false, "ip/firewall/filter.disabled");
        } catch (IllegalArgumentException exception) {
            // Do not claim FastTrack is active from an invalid response.
            return true;
        }
    }
}
