package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.SpeedLimit;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the base-rate portion of a RouterOS DHCP lease {@code rate-limit}.
 *
 * <p>RouterOS documents DHCP rate limits as
 * {@code rx-rate[/tx-rate] [burst settings ...]}. The directions are from the
 * router's perspective: RX is client upload and TX is client download.
 * Consequently the first value maps to {@link SpeedLimit#uploadBps()} and the
 * second value maps to {@link SpeedLimit#downloadBps()}.
 */
public final class RouterOsDhcpRateParser {
    /** DHCP rate-limit documents raw rates plus the k and M decimal suffixes. */
    private static final Pattern RATE = Pattern.compile("([0-9]+)([kKmM]?)");

    private RouterOsDhcpRateParser() {
    }

    /**
     * Parses a blank limit as unlimited. Invalid nonblank values are rejected
     * so callers can make an explicit, conservative fallback decision.
     */
    public static SpeedLimit parse(String value) {
        String normalized = RouterOsValueParser.optionalText(value);
        if (normalized == null) {
            return SpeedLimit.UNLIMITED;
        }

        String baseRates = normalized.split("\\s+", 2)[0];
        String[] rates = baseRates.split("/", -1);
        if (rates.length == 0 || rates.length > 2 || rates[0].isBlank()
                || (rates.length == 2 && rates[1].isBlank())) {
            throw invalid();
        }

        long uploadBps = parseRate(rates[0]);
        long downloadBps = rates.length == 1 ? uploadBps : parseRate(rates[1]);
        return new SpeedLimit(downloadBps, uploadBps);
    }

    private static long parseRate(String value) {
        Matcher matcher = RATE.matcher(value);
        if (!matcher.matches()) {
            throw invalid();
        }

        try {
            long amount = Long.parseLong(matcher.group(1));
            return Math.multiplyExact(amount, multiplier(matcher.group(2)));
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid();
        }
    }

    private static long multiplier(String suffix) {
        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case "" -> 1L;
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            default -> throw invalid();
        };
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Unexpected RouterOS DHCP rate-limit value.");
    }
}
