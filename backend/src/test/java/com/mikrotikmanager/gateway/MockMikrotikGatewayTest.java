package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.SpeedLimit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockMikrotikGatewayTest {

    private final MockMikrotikGateway gateway = new MockMikrotikGateway("10.0.0.1", 443);

    @Test
    void exposesDynamicInterfacesAndRealisticFixtureDevices() {
        assertThat(gateway.connectionStatus().connected()).isTrue();
        assertThat(gateway.connectionStatus().mockMode()).isTrue();
        assertThat(gateway.listInterfaces()).extracting("name").containsExactly("ether1", "ether2", "ether3", "ether4", "ether5");
        assertThat(gateway.listDevices()).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void blockAndUnblockAreIdempotent() {
        String mac = "AA:BB:CC:DD:EE:02";
        gateway.blockDevice(mac);
        gateway.blockDevice(mac);

        assertThat(gateway.findDevice(mac)).get().satisfies(device -> {
            assertThat(device.blocked()).isTrue();
            assertThat(device.status()).isEqualTo(DeviceStatus.BLOCKED);
        });

        gateway.unblockDevice(mac);
        gateway.unblockDevice(mac);

        assertThat(gateway.findDevice(mac)).get().satisfies(device -> {
            assertThat(device.blocked()).isFalse();
            assertThat(device.status()).isEqualTo(DeviceStatus.ONLINE);
        });
    }

    @Test
    void updatesPortAndDeviceSpeedsInMemory() {
        gateway.setPortSpeed("ether2", new SpeedLimit(200_000_000L, 40_000_000L));
        gateway.setDeviceSpeed("AA:BB:CC:DD:EE:01", new SpeedLimit(40_000_000L, 10_000_000L));

        assertThat(gateway.getPortSpeed("ether2")).isEqualTo(new SpeedLimit(200_000_000L, 40_000_000L));
        assertThat(gateway.findDevice("AA:BB:CC:DD:EE:01")).get()
                .extracting(device -> device.speedLimit()).isEqualTo(new SpeedLimit(40_000_000L, 10_000_000L));
    }
}
