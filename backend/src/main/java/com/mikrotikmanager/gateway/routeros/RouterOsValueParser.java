package com.mikrotikmanager.gateway.routeros;

import java.util.Locale;

/**
 * Defensive conversion of RouterOS REST string values. RouterOS sends JSON
 * values as strings, so conversions are kept in one gateway-only location.
 */
public final class RouterOsValueParser {
    private RouterOsValueParser() {
    }

    public static String requiredText(String value, String propertyName) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw invalid(propertyName, "is required");
        }
        return normalized;
    }

    public static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static boolean booleanOrDefault(String value, boolean defaultValue, String propertyName) {
        String normalized = optionalText(value);
        if (normalized == null) {
            return defaultValue;
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "true", "yes" -> true;
            case "false", "no" -> false;
            default -> throw invalid(propertyName, "must be a boolean");
        };
    }

    public static long longOrDefault(String value, long defaultValue, String propertyName) {
        String normalized = optionalText(value);
        if (normalized == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw invalid(propertyName, "must be a 64-bit integer", exception);
        }
    }

    private static IllegalArgumentException invalid(String propertyName, String reason) {
        return new IllegalArgumentException("Unexpected RouterOS value: " + propertyName + " " + reason + ".");
    }

    private static IllegalArgumentException invalid(String propertyName, String reason, Exception cause) {
        return new IllegalArgumentException("Unexpected RouterOS value: " + propertyName + " " + reason + ".", cause);
    }
}
