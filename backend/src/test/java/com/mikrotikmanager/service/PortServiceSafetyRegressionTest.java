package com.mikrotikmanager.service;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.MockMikrotikGateway;
import com.mikrotikmanager.persistence.AuditLogRepository;
import com.mikrotikmanager.persistence.ManagedDeviceRepository;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import com.mikrotikmanager.support.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PortServiceSafetyRegressionTest {
    @Test
    void permitsPortSpeedMutationsInMockModeWhenWritesAreDisabled() {
        MockMikrotikGateway gateway = new MockMikrotikGateway("10.0.0.1", 443);
        ManagedPortRepository portRepository = mock(ManagedPortRepository.class);
        ManagedDeviceRepository deviceRepository = mock(ManagedDeviceRepository.class);
        AuditLogRepository auditRepository = mock(AuditLogRepository.class);
        when(portRepository.findAll()).thenReturn(List.of());
        when(deviceRepository.findByMacAddress(anyString())).thenReturn(Optional.empty());

        PortService service = service(gateway, portRepository, deviceRepository, auditRepository, true, false);

        service.setSpeed("ether3", new SpeedLimit(90_000_000L, 50_000_000L));

        assertThat(gateway.getPortSpeed("ether3"))
                .isEqualTo(new SpeedLimit(90_000_000L, 50_000_000L));
        verify(auditRepository).insert("PORT_SPEED_CHANGED", "PORT", "ether3",
                "download=200000000bps, upload=50000000bps",
                "download=90000000bps, upload=50000000bps", true, null);
    }

    @Test
    void rejectsPortMutationsBeforeGatewayRepositoriesOrAuditWhenRealWritesAreDisabled() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ManagedPortRepository portRepository = mock(ManagedPortRepository.class);
        ManagedDeviceRepository deviceRepository = mock(ManagedDeviceRepository.class);
        AuditLogRepository auditRepository = mock(AuditLogRepository.class);
        DeviceService deviceService = mock(DeviceService.class);
        PortService service = new PortService(gateway, portRepository, deviceService,
                new OperationLockManager(), new AuditService(auditRepository), writeGuard(false, false));

        assertThatThrownBy(() -> service.updateConfiguration(
                "ether2", "Clientes", "", "10.10.10.0/24", "dhcp-clientes", true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("escrita");
        assertThatThrownBy(() -> service.setSpeed("ether2", SpeedLimit.UNLIMITED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("escrita");

        verifyNoInteractions(gateway, portRepository, deviceRepository, auditRepository, deviceService);
    }

    @Test
    void normalizesPortNetworkBeforePersistenceAndKeepsConfigurationAuditFreeOfUserInput() {
        MockMikrotikGateway gateway = new MockMikrotikGateway("10.0.0.1", 443);
        ManagedPortRepository portRepository = mock(ManagedPortRepository.class);
        ManagedDeviceRepository deviceRepository = mock(ManagedDeviceRepository.class);
        AuditLogRepository auditRepository = mock(AuditLogRepository.class);
        when(portRepository.save(any(ManagedPort.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PortService service = service(gateway, portRepository, deviceRepository, auditRepository, true, false);

        service.updateConfiguration("ether2", "Clientes", "password=router-secret", "10.10.10.17/24",
                "dhcp-clientes", true);

        ArgumentCaptor<ManagedPort> savedPort = ArgumentCaptor.forClass(ManagedPort.class);
        verify(portRepository).save(savedPort.capture());
        assertThat(savedPort.getValue().network()).isEqualTo("10.10.10.0/24");

        ArgumentCaptor<String> newAuditValue = ArgumentCaptor.forClass(String.class);
        verify(auditRepository).insert(eq("PORT_CONFIGURATION_UPDATED"), eq("PORT"), eq("ether2"),
                eq("configuration"), newAuditValue.capture(), eq(true), eq(null));
        assertThat(newAuditValue.getValue())
                .isEqualTo("configuration")
                .doesNotContain("router-secret")
                .doesNotContain("password=");
    }

    private PortService service(MikrotikGateway gateway, ManagedPortRepository portRepository,
                                ManagedDeviceRepository deviceRepository, AuditLogRepository auditRepository,
                                boolean mockMode, boolean writeEnabled) {
        DeviceService deviceService = new DeviceService(gateway, deviceRepository, portRepository,
                new OperationLockManager(), new AuditService(auditRepository), writeGuard(mockMode, writeEnabled));
        return new PortService(gateway, portRepository, deviceService, new OperationLockManager(),
                new AuditService(auditRepository), writeGuard(mockMode, writeEnabled));
    }

    private MikrotikWriteGuard writeGuard(boolean mockMode, boolean writeEnabled) {
        return new MikrotikWriteGuard(new MikrotikProperties("10.0.0.1", 443, "admin", "router-secret", true,
                mockMode, writeEnabled));
    }
}
