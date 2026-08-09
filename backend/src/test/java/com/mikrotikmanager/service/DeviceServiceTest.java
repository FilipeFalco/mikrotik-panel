package com.mikrotikmanager.service;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.TrafficRate;
import com.mikrotikmanager.gateway.MikrotikGateway;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DeviceServiceTest {
    private MockMikrotikGateway gateway;
    private DeviceService service;
    private AuditLogRepository auditRepository;

    @BeforeEach
    void setUp() {
        gateway = new MockMikrotikGateway("10.0.0.1", 443);
        ManagedDeviceRepository deviceRepository = mock(ManagedDeviceRepository.class);
        ManagedPortRepository portRepository = mock(ManagedPortRepository.class);
        auditRepository = mock(AuditLogRepository.class);
        when(deviceRepository.findByMacAddress(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(portRepository.findByInterfaceName("ether2")).thenReturn(Optional.of(port("ether2", 100_000_000L, 20_000_000L)));
        service = new DeviceService(gateway, deviceRepository, portRepository, new OperationLockManager(), new AuditService(auditRepository),
                writeGuard(true, false));
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

    @Test
    void permitsDeviceRouterMutationsInMockModeWhenWritesAreDisabled() {
        service.setSpeed("AA:BB:CC:DD:EE:01", new SpeedLimit(80_000_000L, 15_000_000L));
        service.block("AA:BB:CC:DD:EE:01");
        service.unblock("AA:BB:CC:DD:EE:01");

        assertThat(gateway.findDevice("AA:BB:CC:DD:EE:01")).get().satisfies(device -> {
            assertThat(device.speedLimit()).isEqualTo(new SpeedLimit(80_000_000L, 15_000_000L));
            assertThat(device.blocked()).isFalse();
        });
    }

    @Test
    void auditsMetadataChangesWithoutRecordingMetadataContents() {
        service.updateMetadata("AA:BB:CC:DD:EE:01", "Notebook", "anotação privada");

        verify(auditRepository).insert("DEVICE_METADATA_UPDATED", "DEVICE", "AA:BB:CC:DD:EE:01",
                "metadata", "metadata", true, null);
    }

    @Test
    void permitsLocalMetadataUpdatesWhenRealRouterWritesAreDisabled() {
        MikrotikGateway readOnlyGateway = mock(MikrotikGateway.class);
        ManagedDeviceRepository deviceRepository = mock(ManagedDeviceRepository.class);
        ManagedPortRepository portRepository = mock(ManagedPortRepository.class);
        AuditLogRepository auditRepository = mock(AuditLogRepository.class);
        RouterDevice routerDevice = routerDevice("AA:BB:CC:DD:EE:01");
        when(readOnlyGateway.findDevice("AA:BB:CC:DD:EE:01")).thenReturn(Optional.of(routerDevice));
        when(deviceRepository.findByMacAddress("AA:BB:CC:DD:EE:01")).thenReturn(Optional.empty());
        when(portRepository.findByInterfaceName("ether2")).thenReturn(Optional.of(port("ether2", 100_000_000L, 20_000_000L)));
        DeviceService readOnlyService = new DeviceService(readOnlyGateway, deviceRepository, portRepository,
                new OperationLockManager(), new AuditService(auditRepository), writeGuard(false, false));

        readOnlyService.updateMetadata("AA:BB:CC:DD:EE:01", "Notebook", "anotação local");

        verify(deviceRepository).save("AA:BB:CC:DD:EE:01", "Notebook", "anotação local");
        verify(readOnlyGateway, times(2)).findDevice("AA:BB:CC:DD:EE:01");
        verifyNoMoreInteractions(readOnlyGateway);
    }

    @Test
    void rejectsDeviceRouterMutationsBeforeCallingGatewayWhenRealWritesAreDisabled() {
        MikrotikGateway readOnlyGateway = mock(MikrotikGateway.class);
        ManagedDeviceRepository deviceRepository = mock(ManagedDeviceRepository.class);
        ManagedPortRepository portRepository = mock(ManagedPortRepository.class);
        AuditLogRepository auditRepository = mock(AuditLogRepository.class);
        DeviceService readOnlyService = new DeviceService(readOnlyGateway, deviceRepository, portRepository,
                new OperationLockManager(), new AuditService(auditRepository), writeGuard(false, false));

        assertThatThrownBy(() -> readOnlyService.setSpeed("AA:BB:CC:DD:EE:01", SpeedLimit.UNLIMITED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("escrita");
        assertThatThrownBy(() -> readOnlyService.block("AA:BB:CC:DD:EE:01"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("escrita");
        assertThatThrownBy(() -> readOnlyService.unblock("AA:BB:CC:DD:EE:01"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("escrita");

        verifyNoInteractions(readOnlyGateway, deviceRepository, portRepository, auditRepository);
    }

    private MikrotikWriteGuard writeGuard(boolean mockMode, boolean writeEnabled) {
        return new MikrotikWriteGuard(new MikrotikProperties("10.0.0.1", 443, "admin", "secret", true,
                mockMode, writeEnabled));
    }

    private ManagedPort port(String interfaceName, long download, long upload) {
        Instant now = Instant.now();
        return new ManagedPort(1, interfaceName, "Cliente", "", "10.10.10.0/24", "dhcp", ManagedPortRole.CLIENT,
                true, now, now);
    }

    private RouterDevice routerDevice(String macAddress) {
        return new RouterDevice("*A1", macAddress, "Notebook", "10.10.10.21", "dhcp", "ether2",
                DeviceStatus.ONLINE, false, "", SpeedLimit.UNLIMITED, TrafficRate.UNAVAILABLE, Instant.now());
    }
}
