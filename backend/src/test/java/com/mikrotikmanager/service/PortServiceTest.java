package com.mikrotikmanager.service;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.MockMikrotikGateway;
import com.mikrotikmanager.persistence.AuditLogRepository;
import com.mikrotikmanager.persistence.ManagedDeviceRepository;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import com.mikrotikmanager.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortServiceTest {
    private MockMikrotikGateway gateway;
    private PortService service;

    @BeforeEach
    void setUp() {
        gateway = new MockMikrotikGateway("10.0.0.1", 443);
        service = serviceFor(gateway);
    }

    @Test
    void listPortsLoadsGatewayCollectionsOnceAndCorrelatesDevicesInMemory() {
        CountingGateway countingGateway = new CountingGateway(new MockMikrotikGateway("10.0.0.1", 443));
        PortService countingService = serviceFor(countingGateway);

        List<PortView> ports = countingService.listPorts();

        assertThat(countingGateway.listInterfacesCalls).isEqualTo(1);
        assertThat(countingGateway.listDevicesCalls).isEqualTo(1);
        assertThat(countingGateway.listPortSpeedsCalls).isEqualTo(1);
        assertThat(countingGateway.getPortSpeedCalls).isZero();
        assertThat(ports).hasSize(5);
        assertThat(ports.stream()
                .filter(port -> "ether2".equals(port.routerInterface().name()))
                .findFirst()
                .orElseThrow()
                .devices()).hasSize(5);
    }

    @Test
    void refusesPortReductionBelowAnIndividualDeviceDownloadLimitWithoutChangingTheGateway() {
        SpeedLimit previous = new SpeedLimit(100_000_000L, 100_000_000L);
        gateway.setPortSpeed("ether3", previous);

        assertThatThrownBy(() -> service.setSpeed("ether3", new SpeedLimit(50_000_000L, 100_000_000L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("não pode ser menor");

        assertThat(gateway.getPortSpeed("ether3")).isEqualTo(previous);
    }

    @Test
    void refusesPortReductionBelowAnIndividualDeviceUploadLimit() {
        gateway.setPortSpeed("ether3", new SpeedLimit(100_000_000L, 100_000_000L));
        gateway.setDeviceSpeed("AA:BB:CC:DD:EF:01", new SpeedLimit(20_000_000L, 80_000_000L));

        assertThatThrownBy(() -> service.setSpeed("ether3", new SpeedLimit(100_000_000L, 50_000_000L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("não pode ser menor");
    }

    @Test
    void allowsPortLimitAboveAllIndividualDeviceLimits() {
        gateway.setPortSpeed("ether3", new SpeedLimit(100_000_000L, 100_000_000L));

        service.setSpeed("ether3", new SpeedLimit(90_000_000L, 90_000_000L));

        assertThat(gateway.getPortSpeed("ether3")).isEqualTo(new SpeedLimit(90_000_000L, 90_000_000L));
    }

    @Test
    void allowsUnlimitedPortWithHigherIndividualDeviceLimit() {
        gateway.setDeviceSpeed("AA:BB:CC:DD:EF:01", new SpeedLimit(200_000_000L, 200_000_000L));

        service.setSpeed("ether3", SpeedLimit.UNLIMITED);

        assertThat(gateway.getPortSpeed("ether3")).isEqualTo(SpeedLimit.UNLIMITED);
    }

    private PortService serviceFor(MikrotikGateway gateway) {
        ManagedPortRepository portRepository = mock(ManagedPortRepository.class);
        ManagedDeviceRepository deviceRepository = mock(ManagedDeviceRepository.class);
        when(portRepository.findAll()).thenReturn(List.of());
        when(deviceRepository.findByMacAddress(anyString())).thenReturn(Optional.empty());

        AuditService auditService = new AuditService(mock(AuditLogRepository.class));
        DeviceService deviceService = new DeviceService(
                gateway,
                deviceRepository,
                portRepository,
                new OperationLockManager(),
                auditService,
                writeGuard());
        return new PortService(gateway, portRepository, deviceService, new OperationLockManager(), auditService, writeGuard());
    }

    private MikrotikWriteGuard writeGuard() {
        return new MikrotikWriteGuard(new MikrotikProperties("10.0.0.1", 443, "admin", "secret", true,
                true, false));
    }

    private static final class CountingGateway implements MikrotikGateway {
        private final MikrotikGateway delegate;
        private int listInterfacesCalls;
        private int listDevicesCalls;
        private int listPortSpeedsCalls;
        private int getPortSpeedCalls;

        private CountingGateway(MikrotikGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public GatewayConnectionStatus connectionStatus() {
            return delegate.connectionStatus();
        }

        @Override
        public List<RouterInterface> listInterfaces() {
            listInterfacesCalls++;
            return delegate.listInterfaces();
        }

        @Override
        public List<RouterDevice> listDevices() {
            listDevicesCalls++;
            return delegate.listDevices();
        }

        @Override
        public Map<String, SpeedLimit> listPortSpeeds() {
            listPortSpeedsCalls++;
            return delegate.listPortSpeeds();
        }

        @Override
        public Optional<RouterDevice> findDevice(String macAddress) {
            return delegate.findDevice(macAddress);
        }

        @Override
        public SpeedLimit getPortSpeed(String interfaceName) {
            getPortSpeedCalls++;
            return delegate.getPortSpeed(interfaceName);
        }

        @Override
        public void setPortSpeed(String interfaceName, SpeedLimit speedLimit) {
            delegate.setPortSpeed(interfaceName, speedLimit);
        }

        @Override
        public void setDeviceSpeed(String macAddress, SpeedLimit speedLimit) {
            delegate.setDeviceSpeed(macAddress, speedLimit);
        }

        @Override
        public void blockDevice(String macAddress) {
            delegate.blockDevice(macAddress);
        }

        @Override
        public void unblockDevice(String macAddress) {
            delegate.unblockDevice(macAddress);
        }
    }
}
