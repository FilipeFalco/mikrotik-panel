package com.mikrotikmanager.gateway.routeros;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.TrafficRate;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsDhcpLeaseDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsDhcpServerDto;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterOsDhcpMapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-08-09T12:00:00Z");
    private final RouterOsDhcpMapper mapper = new RouterOsDhcpMapper(Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void deserializesOptionalDhcpTransportFieldsAndIgnoresFutureProperties() throws Exception {
        RouterOsDhcpLeaseDto lease = objectMapper.readValue("""
                {
                  ".id": "*A1",
                  "address": "10.10.10.21",
                  "mac-address": "aa:bb:cc:dd:ee:01",
                  "host-name": "Galaxy S25",
                  "server": "dhcp-cliente1",
                  "status": "bound",
                  "block-access": "false",
                  "comment": "Celular principal",
                  "last-seen": "2m30s",
                  "rate-limit": "512k/1M",
                  "dynamic": "true",
                  "disabled": "false",
                  "future-routeros-property": "ignored"
                }
                """, RouterOsDhcpLeaseDto.class);
        RouterOsDhcpServerDto server = objectMapper.readValue("""
                {
                  ".id": "*1",
                  "name": "dhcp-cliente1",
                  "interface": "ether2",
                  "disabled": "false",
                  "future-routeros-property": "ignored"
                }
                """, RouterOsDhcpServerDto.class);

        assertThat(lease.id()).isEqualTo("*A1");
        assertThat(lease.macAddress()).isEqualTo("aa:bb:cc:dd:ee:01");
        assertThat(lease.blockAccess()).isEqualTo("false");
        assertThat(lease.rateLimit()).isEqualTo("512k/1M");
        assertThat(server.interfaceName()).isEqualTo("ether2");
    }

    @Test
    void correlatesLeaseServerToDhcpServerInterfaceAndMapsKnownFields() {
        RouterOsDhcpLeaseDto lease = lease("*A1", "aa:bb:cc:dd:ee:01", "dhcp-cliente1", "bound", "false",
                "2m30s", "512k/1M");

        List<RouterDevice> devices = mapper.toRouterDevices(
                List.of(server("dhcp-cliente1", "ether2")), List.of(lease));

        assertThat(devices).singleElement().satisfies(device -> {
            assertThat(device.leaseId()).isEqualTo("*A1");
            assertThat(device.macAddress()).isEqualTo("AA:BB:CC:DD:EE:01");
            assertThat(device.hostname()).isEqualTo("Galaxy S25");
            assertThat(device.ipAddress()).isEqualTo("10.10.10.21");
            assertThat(device.dhcpServer()).isEqualTo("dhcp-cliente1");
            assertThat(device.interfaceName()).isEqualTo("ether2");
            assertThat(device.status()).isEqualTo(DeviceStatus.ONLINE);
            assertThat(device.blocked()).isFalse();
            assertThat(device.leaseComment()).isEqualTo("Celular principal");
            // DHCP's rx/tx is router-centric: rx is client upload, tx is client download.
            assertThat(device.speedLimit()).isEqualTo(new SpeedLimit(1_000_000L, 512_000L));
            assertThat(device.traffic()).isEqualTo(TrafficRate.UNAVAILABLE);
            assertThat(device.lastSeenAt()).isEqualTo(now.minusSeconds(150));
        });
    }

    @Test
    void ignoresOnlyLeaseWithoutMatchingDhcpServerAndKeepsOtherDevices() {
        RouterOsDhcpLeaseDto valid = lease("*A1", "AA:BB:CC:DD:EE:01", "dhcp-cliente1", "bound", "false", null, null);
        RouterOsDhcpLeaseDto unmapped = lease("*A2", "AA:BB:CC:DD:EE:02", "dhcp-inexistente", "bound", "false", null, null);

        List<RouterDevice> devices = mapper.toRouterDevices(
                List.of(server("dhcp-cliente1", "ether2")), List.of(valid, unmapped));

        assertThat(devices).extracting(RouterDevice::macAddress).containsExactly("AA:BB:CC:DD:EE:01");
        assertThat(devices).extracting(RouterDevice::interfaceName).containsExactly("ether2");
    }

    @Test
    void ignoresLeasesWithMissingOrUnusableMacWithoutInventingIdentifiers() {
        RouterOsDhcpLeaseDto noMac = lease("*A1", null, "dhcp-cliente1", "bound", "false", null, null);
        RouterOsDhcpLeaseDto invalidMac = lease("*A2", "not-a-mac", "dhcp-cliente1", "bound", "false", null, null);
        RouterOsDhcpLeaseDto valid = lease("*A3", "AA:BB:CC:DD:EE:03", "dhcp-cliente1", "bound", "false", null, null);

        List<RouterDevice> devices = mapper.toRouterDevices(
                List.of(server("dhcp-cliente1", "ether2")), List.of(noMac, invalidMac, valid));

        assertThat(devices).extracting(RouterDevice::macAddress).containsExactly("AA:BB:CC:DD:EE:03");
    }

    @Test
    void mapsBlockedBoundAndUncertainLeaseStatusesConservatively() {
        RouterOsDhcpLeaseDto blocked = lease("*A1", "AA:BB:CC:DD:EE:01", "dhcp-cliente1", "bound", "true", null, null);
        RouterOsDhcpLeaseDto bound = lease("*A2", "AA:BB:CC:DD:EE:02", "dhcp-cliente1", "bound", "false", null, null);
        RouterOsDhcpLeaseDto waiting = lease("*A3", "AA:BB:CC:DD:EE:03", "dhcp-cliente1", "waiting", "false", null, null);

        List<RouterDevice> devices = mapper.toRouterDevices(
                List.of(server("dhcp-cliente1", "ether2")), List.of(blocked, bound, waiting));

        assertThat(devices).extracting(RouterDevice::status)
                .containsExactly(DeviceStatus.BLOCKED, DeviceStatus.ONLINE, DeviceStatus.UNKNOWN);
        assertThat(devices).extracting(RouterDevice::blocked).containsExactly(true, false, false);
    }

    @Test
    void toleratesAbsentOptionalLeasePropertiesAndMalformedOptionalValues() {
        RouterOsDhcpLeaseDto sparse = new RouterOsDhcpLeaseDto(
                null, null, "AA:BB:CC:DD:EE:01", null, "dhcp-cliente1", null,
                "unexpected", null, "never", "not-a-rate", null, null);

        List<RouterDevice> devices = mapper.toRouterDevices(
                List.of(server("dhcp-cliente1", "ether2")), List.of(sparse));

        assertThat(devices).singleElement().satisfies(device -> {
            assertThat(device.leaseId()).isNull();
            assertThat(device.hostname()).isNull();
            assertThat(device.ipAddress()).isNull();
            assertThat(device.leaseComment()).isNull();
            assertThat(device.status()).isEqualTo(DeviceStatus.UNKNOWN);
            assertThat(device.blocked()).isFalse();
            assertThat(device.speedLimit()).isEqualTo(SpeedLimit.UNLIMITED);
            assertThat(device.lastSeenAt()).isNull();
        });
    }

    @Test
    void leavesMalformedLastSeenAbsentWithoutDroppingTheLease() {
        RouterOsDhcpLeaseDto lease = lease("*A1", "AA:BB:CC:DD:EE:01", "dhcp-cliente1", "bound", "false",
                "not-a-routeros-duration", null);

        List<RouterDevice> devices = mapper.toRouterDevices(
                List.of(server("dhcp-cliente1", "ether2")), List.of(lease));

        assertThat(devices).singleElement().extracting(RouterDevice::lastSeenAt).isNull();
    }

    @Test
    void parsesDhcpRateLimitDirectionsAndRouterOsUnits() {
        assertThat(RouterOsDhcpRateParser.parse("512k/1M"))
                .isEqualTo(new SpeedLimit(1_000_000L, 512_000L));
        assertThat(RouterOsDhcpRateParser.parse("20M"))
                .isEqualTo(new SpeedLimit(20_000_000L, 20_000_000L));
        assertThat(RouterOsDhcpRateParser.parse("1000000/1000k"))
                .isEqualTo(new SpeedLimit(1_000_000L, 1_000_000L));
        assertThat(RouterOsDhcpRateParser.parse("512k/1M 1M/2M"))
                .isEqualTo(new SpeedLimit(1_000_000L, 512_000L));
        assertThat(RouterOsDhcpRateParser.parse(" ")).isEqualTo(SpeedLimit.UNLIMITED);
    }

    @Test
    void rejectsInvalidOrOverflowingDhcpRatesInsteadOfSilentlyOverflowing() {
        assertThatThrownBy(() -> RouterOsDhcpRateParser.parse("1T"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected RouterOS DHCP rate-limit value.");
        assertThatThrownBy(() -> RouterOsDhcpRateParser.parse("1G"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected RouterOS DHCP rate-limit value.");
        assertThatThrownBy(() -> RouterOsDhcpRateParser.parse("9223372036854775807G"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected RouterOS DHCP rate-limit value.");
        assertThatThrownBy(() -> RouterOsDhcpRateParser.parse("1M/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected RouterOS DHCP rate-limit value.");
    }

    private RouterOsDhcpServerDto server(String name, String interfaceName) {
        return new RouterOsDhcpServerDto("*1", name, interfaceName, "false");
    }

    private RouterOsDhcpLeaseDto lease(
            String id,
            String macAddress,
            String server,
            String status,
            String blockAccess,
            String lastSeen,
            String rateLimit
    ) {
        return new RouterOsDhcpLeaseDto(
                id,
                "10.10.10.21",
                macAddress,
                "Galaxy S25",
                server,
                status,
                blockAccess,
                "Celular principal",
                lastSeen,
                rateLimit,
                "true",
                "false"
        );
    }
}
