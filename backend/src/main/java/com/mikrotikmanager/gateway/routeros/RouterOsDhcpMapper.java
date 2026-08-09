package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.TrafficRate;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsDhcpLeaseDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsDhcpServerDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts DHCP server and lease responses into RouterDevice values.
 *
 * <p>The server-to-interface lookup is built once and the leases are then
 * correlated in memory. This deliberately avoids a RouterOS request per
 * lease. A lease that cannot be safely correlated is omitted while its other
 * valid peers remain usable.
 */
public final class RouterOsDhcpMapper {
    private static final Logger log = LoggerFactory.getLogger(RouterOsDhcpMapper.class);
    private static final Pattern LAST_SEEN_COMPONENT = Pattern.compile("(\\d+)(w|d|h|ms|us|ns|m|s)");

    private final Clock clock;

    public RouterOsDhcpMapper() {
        this(Clock.systemUTC());
    }

    RouterOsDhcpMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Maps correlated leases only. Missing/unusable MAC addresses and leases
     * without a matching DHCP server interface are intentionally excluded;
     * inventing a device identifier or interface would be unsafe.
     */
    public List<RouterDevice> toRouterDevices(
            List<RouterOsDhcpServerDto> dhcpServers,
            List<RouterOsDhcpLeaseDto> leases
    ) {
        Objects.requireNonNull(dhcpServers, "dhcpServers");
        Objects.requireNonNull(leases, "leases");

        Map<String, String> interfacesByServerName = interfacesByServerName(dhcpServers);
        MappingWarnings warnings = new MappingWarnings();
        List<RouterDevice> devices = new ArrayList<>();

        for (RouterOsDhcpLeaseDto lease : leases) {
            if (lease == null) {
                warnings.missingOrInvalidMac++;
                continue;
            }

            String macAddress = normalizeMac(lease.macAddress(), warnings);
            if (macAddress == null) {
                continue;
            }

            String dhcpServer = RouterOsValueParser.optionalText(lease.server());
            String interfaceName = dhcpServer == null ? null : interfacesByServerName.get(dhcpServer);
            if (interfaceName == null) {
                warnings.missingServerMapping++;
                continue;
            }

            boolean blocked = blocked(lease.blockAccess(), warnings);
            devices.add(new RouterDevice(
                    RouterOsValueParser.optionalText(lease.id()),
                    macAddress,
                    RouterOsValueParser.optionalText(lease.hostName()),
                    RouterOsValueParser.optionalText(lease.address()),
                    dhcpServer,
                    interfaceName,
                    deviceStatus(lease.status(), blocked),
                    blocked,
                    RouterOsValueParser.optionalText(lease.comment()),
                    speedLimit(lease.rateLimit(), warnings),
                    TrafficRate.UNAVAILABLE,
                    lastSeenAt(lease.lastSeen(), warnings)
            ));
        }

        warnings.log();
        return List.copyOf(devices);
    }

    private Map<String, String> interfacesByServerName(List<RouterOsDhcpServerDto> dhcpServers) {
        Map<String, String> interfacesByServerName = new HashMap<>();
        for (RouterOsDhcpServerDto server : dhcpServers) {
            if (server == null) {
                continue;
            }
            String name = RouterOsValueParser.optionalText(server.name());
            String interfaceName = RouterOsValueParser.optionalText(server.interfaceName());
            if (name != null && interfaceName != null) {
                interfacesByServerName.putIfAbsent(name, interfaceName);
            }
        }
        return interfacesByServerName;
    }

    private String normalizeMac(String candidate, MappingWarnings warnings) {
        String macAddress = RouterOsValueParser.optionalText(candidate);
        if (macAddress == null) {
            warnings.missingOrInvalidMac++;
            return null;
        }
        try {
            // Keep the application's one authoritative MAC normalization rule.
            return ManagedResourceIdentifier.normalizeMac(macAddress);
        } catch (IllegalArgumentException exception) {
            warnings.missingOrInvalidMac++;
            return null;
        }
    }

    private boolean blocked(String blockAccess, MappingWarnings warnings) {
        try {
            return RouterOsValueParser.booleanOrDefault(blockAccess, false, "dhcp-lease.block-access");
        } catch (IllegalArgumentException exception) {
            warnings.invalidBlockAccess++;
            return false;
        }
    }

    private DeviceStatus deviceStatus(String leaseStatus, boolean blocked) {
        if (blocked) {
            return DeviceStatus.BLOCKED;
        }
        String normalizedStatus = RouterOsValueParser.optionalText(leaseStatus);
        return normalizedStatus != null && "bound".equalsIgnoreCase(normalizedStatus)
                ? DeviceStatus.ONLINE
                : DeviceStatus.UNKNOWN;
    }

    private SpeedLimit speedLimit(String rateLimit, MappingWarnings warnings) {
        try {
            return RouterOsDhcpRateParser.parse(rateLimit);
        } catch (IllegalArgumentException exception) {
            warnings.invalidRateLimit++;
            return SpeedLimit.UNLIMITED;
        }
    }

    /**
     * RouterOS commonly returns last-seen as an elapsed duration (for example
     * {@code 2w3d4h5m6s}). It is converted only when every component is known;
     * values such as {@code never}, malformed input, and overflow are left
     * absent rather than guessed.
     */
    private Instant lastSeenAt(String lastSeen, MappingWarnings warnings) {
        String value = RouterOsValueParser.optionalText(lastSeen);
        if (value == null || "never".equalsIgnoreCase(value)) {
            return null;
        }

        try {
            Duration elapsed = parseElapsedDuration(value);
            return clock.instant().minus(elapsed);
        } catch (DateTimeException | IllegalArgumentException | ArithmeticException exception) {
            warnings.invalidLastSeen++;
            return null;
        }
    }

    private Duration parseElapsedDuration(String value) {
        Matcher matcher = LAST_SEEN_COMPONENT.matcher(value);
        int position = 0;
        Duration elapsed = Duration.ZERO;
        while (matcher.find()) {
            if (matcher.start() != position) {
                throw new IllegalArgumentException("Unexpected RouterOS DHCP last-seen value.");
            }
            long amount = Long.parseLong(matcher.group(1));
            elapsed = elapsed.plus(scaledDuration(amount, matcher.group(2)));
            position = matcher.end();
        }
        if (position != value.length()) {
            throw new IllegalArgumentException("Unexpected RouterOS DHCP last-seen value.");
        }
        return elapsed;
    }

    private Duration scaledDuration(long amount, String unit) {
        return switch (unit) {
            case "w" -> Duration.ofDays(Math.multiplyExact(amount, 7));
            case "d" -> Duration.ofDays(amount);
            case "h" -> Duration.ofHours(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "ms" -> Duration.ofMillis(amount);
            case "us" -> Duration.ofNanos(Math.multiplyExact(amount, 1_000));
            case "ns" -> Duration.ofNanos(amount);
            default -> throw new IllegalArgumentException("Unexpected RouterOS DHCP last-seen value.");
        };
    }

    private static final class MappingWarnings {
        private int missingOrInvalidMac;
        private int missingServerMapping;
        private int invalidBlockAccess;
        private int invalidRateLimit;
        private int invalidLastSeen;

        private void log() {
            if (missingOrInvalidMac > 0) {
                log.warn("Ignored {} DHCP lease(s) with no usable MAC address.", missingOrInvalidMac);
            }
            if (missingServerMapping > 0) {
                log.warn("Ignored {} DHCP lease(s) without a DHCP server-to-interface mapping.", missingServerMapping);
            }
            if (invalidBlockAccess > 0) {
                log.warn("Defaulted block-access to false for {} DHCP lease(s) with an invalid value.", invalidBlockAccess);
            }
            if (invalidRateLimit > 0) {
                log.warn("Defaulted rate-limit to unlimited for {} DHCP lease(s) with an invalid value.", invalidRateLimit);
            }
            if (invalidLastSeen > 0) {
                log.warn("Omitted last-seen for {} DHCP lease(s) with an unsupported value.", invalidLastSeen);
            }
        }
    }
}
