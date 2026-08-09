package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.MockMikrotikGateway;
import com.mikrotikmanager.persistence.AuditLogRepository;
import com.mikrotikmanager.persistence.ManagedDeviceRepository;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import com.mikrotikmanager.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceServiceTest {
    private MockMikrotikGateway gateway;
    private DeviceService service;

    @BeforeEach
    void setUp() {
        gateway = new MockMikrotikGateway("10.0.0.1", 443);
        ManagedDeviceRepository deviceRepository = mock(ManagedDeviceRepository.class);
        ManagedPortRepository portRepository = mock(ManagedPortRepository.class);
        AuditLogRepository auditRepository = mock(AuditLogRepository.class);
        when(deviceRepository.findByMacAddress(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(portRepository.findByInterfaceName("ether2")).thenReturn(Optional.of(port("ether2", 100_000_000L, 20_000_000L)));
        service = new DeviceService(gateway, deviceRepository, portRepository, new OperationLockManager(), new AuditService(auditRepository));
    }

    @Test
    void refusesIndividualLimitAboveTheTotalPortLimit() {
        assertThatThrownBy(() -> service.setSpeed("AA:BB:CC:DD:EE:01", new SpeedLimit(101_000_000L, 10_000_000L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("não pode ultrapassar");
    }

    @Test
    void updatesIndividualLimitWhenItFitsWithinPortLimit() {
        service.setSpeed("AA:BB:CC:DD:EE:01", new SpeedLimit(80_000_000L, 15_000_000L));

        assertThat(gateway.findDevice("AA:BB:CC:DD:EE:01")).get()
                .extracting(device -> device.speedLimit())
                .isEqualTo(new SpeedLimit(80_000_000L, 15_000_000L));
    }

    private ManagedPort port(String interfaceName, long download, long upload) {
        Instant now = Instant.now();
        return new ManagedPort(1, interfaceName, "Cliente", "", "10.10.10.0/24", "dhcp", true, now, now);
    }
}
