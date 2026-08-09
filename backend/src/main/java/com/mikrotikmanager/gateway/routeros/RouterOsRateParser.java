package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.SpeedLimit;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for RouterOS queue rates.
 *
 * <p>RouterOS simple-queue {@code max-limit} is documented as
 * {@code upload/download}. The application domain deliberately uses
 * {@code SpeedLimit(download, upload)}, so the order is inverted only here at
 * the RouterOS boundary. The parser accepts RouterOS-style decimal rates with
 * the k, M and G suffixes; malformed, empty and overflowing max-limit values
 * result in {@link Optional#empty()} rather than an invented limit.
 */
public final class RouterOsRateParser {
    private static final Pattern RATE = Pattern.compile("^([0-9]+)([kKmMgG])?$");
    private static final long KILOBITS = 1_000L;
    private static final long MEGABITS = 1_000_000L;
    private static final long GIGABITS = 1_000_000_000L;

    private RouterOsRateParser() {
    }

    /**
     * Parses one RouterOS rate as bits per second.
     *
     * @throws IllegalArgumentException when the value is not a supported
     *                                  RouterOS decimal rate or cannot fit in
     *                                  a signed 64-bit application value
     */
    public static long parseBitsPerSecond(String rawRate) {
        String rate = RouterOsValueParser.optionalText(rawRate);
        if (rate == null) {
            throw invalidRate();
        }

        Matcher matcher = RATE.matcher(rate);
        if (!matcher.matches()) {
            throw invalidRate();
        }

        final long numericPart;
        try {
            numericPart = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalidRate(exception);
        }

        try {
            return Math.multiplyExact(numericPart, multiplier(matcher.group(2)));
        } catch (ArithmeticException exception) {
            throw invalidRate(exception);
        }
    }

    /**
     * Parses the RouterOS {@code max-limit} property without allowing one bad
     * queue entry to take down the whole read-only list. A returned empty value
     * means no trustworthy limit was derived from this transport value.
     */
    public static Optional<SpeedLimit> parseSimpleQueueMaxLimit(String rawMaxLimit) {
        String maxLimit = RouterOsValueParser.optionalText(rawMaxLimit);
        if (maxLimit == null) {
            return Optional.empty();
        }

        String[] directions = maxLimit.split("/", -1);
        if (directions.length != 2) {
            return Optional.empty();
        }

        try {
            // RouterOS order: upload/download. Domain order: download/upload.
            long uploadBps = parseBitsPerSecond(directions[0]);
            long downloadBps = parseBitsPerSecond(directions[1]);
            return Optional.of(new SpeedLimit(downloadBps, uploadBps));
        } catch (IllegalArgumentException exception) {
            // Do not expose or log the raw RouterOS value. The caller can safely
            // skip this single malformed queue while retaining other results.
            return Optional.empty();
        }
    }

    private static long multiplier(String suffix) {
        if (suffix == null) {
            return 1L;
        }
        return switch (Character.toUpperCase(suffix.charAt(0))) {
            case 'K' -> KILOBITS;
            case 'M' -> MEGABITS;
            case 'G' -> GIGABITS;
            default -> throw invalidRate();
        };
    }

    private static IllegalArgumentException invalidRate() {
        return new IllegalArgumentException("Unexpected RouterOS queue rate.");
    }

    private static IllegalArgumentException invalidRate(Exception cause) {
        return new IllegalArgumentException("Unexpected RouterOS queue rate.", cause);
    }
}
