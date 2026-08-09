package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.TrafficRate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** In-memory RouterOS substitute used for local development and automated tests. */
public final class MockMikrotikGateway implements MikrotikGateway {
    private final String host;
    private final int port;
    private final Map<String, RouterInterface> interfaces = new LinkedHashMap<>();
    private final Map<String, MockDeviceState> devices = new LinkedHashMap<>();
    private final Map<String, SpeedLimit> portSpeeds = new LinkedHashMap<>();

    public MockMikrotikGateway(String host, int port) {
        this.host = host;
        this.port = port;
        addFixtures();
    }

    @Override
    public synchronized GatewayConnectionStatus connectionStatus() {
        return new GatewayConnectionStatus(
                true, true, host, port, "7.18.2 (mock)", 2L,
                "Executando com dados simulados; nenhuma chamada foi enviada ao MikroTik.", true);
    }

    @Override
    public synchronized List<RouterInterface> listInterfaces() {
        return List.copyOf(interfaces.values());
    }

    @Override
    public synchronized List<RouterDevice> listDevices() {
        return devices.values().stream().map(MockDeviceState::toDevice).toList();
    }

    @Override
    public synchronized Optional<RouterDevice> findDevice(String macAddress) {
        return Optional.ofNullable(devices.get(normalize(macAddress))).map(MockDeviceState::toDevice);
    }

    @Override
    public synchronized SpeedLimit getPortSpeed(String interfaceName) {
        ensureInterface(interfaceName);
        return portSpeeds.getOrDefault(interfaceName, SpeedLimit.UNLIMITED);
    }

    @Override
    public synchronized void setPortSpeed(String interfaceName, SpeedLimit speedLimit) {
        ensureInterface(interfaceName);
        portSpeeds.put(interfaceName, speedLimit);
    }

    @Override
    public synchronized void setDeviceSpeed(String macAddress, SpeedLimit speedLimit) {
        findState(macAddress).speedLimit = speedLimit;
    }

    @Override
    public synchronized void blockDevice(String macAddress) {
        MockDeviceState device = findState(macAddress);
        if (!device.blocked) {
            device.statusBeforeBlock = device.status;
            device.status = DeviceStatus.BLOCKED;
            device.blocked = true;
        }
    }

    @Override
    public synchronized void unblockDevice(String macAddress) {
        MockDeviceState device = findState(macAddress);
        if (device.blocked) {
            device.blocked = false;
            device.status = device.statusBeforeBlock == DeviceStatus.BLOCKED ? DeviceStatus.UNKNOWN : device.statusBeforeBlock;
        }
    }

    private void addFixtures() {
        interfaces.put("ether1", new RouterInterface("ether1", "ether", true, false,
                new TrafficRate(423_000_000L, 87_000_000L)));
        interfaces.put("ether2", new RouterInterface("ether2", "ether", true, false,
                new TrafficRate(72_300_000L, 8_700_000L)));
        interfaces.put("ether3", new RouterInterface("ether3", "ether", true, false,
                new TrafficRate(118_600_000L, 19_800_000L)));
        interfaces.put("ether4", new RouterInterface("ether4", "ether", true, false,
                new TrafficRate(216_400_000L, 42_100_000L)));
        interfaces.put("ether5", new RouterInterface("ether5", "ether", false, false,
                TrafficRate.UNAVAILABLE));

        portSpeeds.put("ether2", new SpeedLimit(100_000_000L, 20_000_000L));
        portSpeeds.put("ether3", new SpeedLimit(200_000_000L, 50_000_000L));
        portSpeeds.put("ether4", new SpeedLimit(500_000_000L, 100_000_000L));

        addDevice("*A1", "AA:BB:CC:DD:EE:01", "Galaxy-S25", "10.10.10.21", "dhcp-joao", "ether2",
                DeviceStatus.ONLINE, false, new SpeedLimit(20_000_000L, 5_000_000L), new TrafficRate(13_200_000L, 1_700_000L), "Celular principal");
        addDevice("*A2", "AA:BB:CC:DD:EE:02", "DESKTOP-E8FH29", "10.10.10.22", "dhcp-joao", "ether2",
                DeviceStatus.ONLINE, false, new SpeedLimit(50_000_000L, 10_000_000L), new TrafficRate(32_400_000L, 2_100_000L), "");
        addDevice("*A3", "AA:BB:CC:DD:EE:03", "Samsung-TV", "10.10.10.30", "dhcp-joao", "ether2",
                DeviceStatus.OFFLINE, false, SpeedLimit.UNLIMITED, TrafficRate.UNAVAILABLE, "Sala");
        addDevice("*A4", "AA:BB:CC:DD:EE:04", "Xbox-Joao", "10.10.10.41", "dhcp-joao", "ether2",
                DeviceStatus.BLOCKED, true, new SpeedLimit(30_000_000L, 5_000_000L), TrafficRate.UNAVAILABLE, "Horário de estudo");
        addDevice("*A5", "AA:BB:CC:DD:EE:05", "iPhone-de-Ana", "10.10.10.48", "dhcp-joao", "ether2",
                DeviceStatus.ONLINE, false, SpeedLimit.UNLIMITED, new TrafficRate(5_100_000L, 840_000L), "");

        addDevice("*B1", "AA:BB:CC:DD:EF:01", "MacBook-Maria", "10.10.20.11", "dhcp-maria", "ether3",
                DeviceStatus.ONLINE, false, new SpeedLimit(80_000_000L, 20_000_000L), new TrafficRate(51_000_000L, 5_800_000L), "Home office");
        addDevice("*B2", "AA:BB:CC:DD:EF:02", "Galaxy-Tab", "10.10.20.24", "dhcp-maria", "ether3",
                DeviceStatus.ONLINE, false, SpeedLimit.UNLIMITED, new TrafficRate(8_500_000L, 940_000L), "");
        addDevice("*B3", "AA:BB:CC:DD:EF:03", "LG-webOS-TV", "10.10.20.31", "dhcp-maria", "ether3",
                DeviceStatus.ONLINE, false, SpeedLimit.UNLIMITED, new TrafficRate(24_200_000L, 1_200_000L), "Sala de TV");
        addDevice("*B4", "AA:BB:CC:DD:EF:04", "Echo-Dot", "10.10.20.38", "dhcp-maria", "ether3",
                DeviceStatus.OFFLINE, false, SpeedLimit.UNLIMITED, TrafficRate.UNAVAILABLE, "");

        addDevice("*C1", "AA:BB:CC:DD:F0:01", "PC-RECEPCAO", "10.10.30.10", "dhcp-escritorio", "ether4",
                DeviceStatus.ONLINE, false, new SpeedLimit(150_000_000L, 50_000_000L), new TrafficRate(71_000_000L, 9_000_000L), "Recepção");
        addDevice("*C2", "AA:BB:CC:DD:F0:02", "Impressora-HP", "10.10.30.15", "dhcp-escritorio", "ether4",
                DeviceStatus.ONLINE, false, SpeedLimit.UNLIMITED, new TrafficRate(1_700_000L, 2_400_000L), "");
        addDevice("*C3", "AA:BB:CC:DD:F0:03", "NAS-Escritorio", "10.10.30.20", "dhcp-escritorio", "ether4",
                DeviceStatus.ONLINE, false, SpeedLimit.UNLIMITED, new TrafficRate(110_000_000L, 28_000_000L), "Backup local");
    }

    private void addDevice(String leaseId, String mac, String hostname, String ip, String dhcpServer, String interfaceName,
                           DeviceStatus status, boolean blocked, SpeedLimit speed, TrafficRate traffic, String comment) {
        devices.put(normalize(mac), new MockDeviceState(leaseId, normalize(mac), hostname, ip, dhcpServer, interfaceName,
                status, blocked, status, comment, speed, traffic, Instant.now().minus(3, ChronoUnit.MINUTES)));
    }

    private MockDeviceState findState(String macAddress) {
        MockDeviceState state = devices.get(normalize(macAddress));
        if (state == null) {
            throw new MikrotikGatewayException("Dispositivo não encontrado no MikroTik.");
        }
        return state;
    }

    private void ensureInterface(String interfaceName) {
        if (!interfaces.containsKey(interfaceName)) {
            throw new MikrotikGatewayException("Interface não encontrada no MikroTik.");
        }
    }

    private String normalize(String macAddress) {
        return ManagedResourceIdentifier.normalizeMac(macAddress).toUpperCase(Locale.ROOT);
    }

    private static final class MockDeviceState {
        private final String leaseId;
        private final String macAddress;
        private final String hostname;
        private final String ipAddress;
        private final String dhcpServer;
        private final String interfaceName;
        private DeviceStatus status;
        private boolean blocked;
        private DeviceStatus statusBeforeBlock;
        private final String leaseComment;
        private SpeedLimit speedLimit;
        private final TrafficRate traffic;
        private final Instant lastSeenAt;

        private MockDeviceState(String leaseId, String macAddress, String hostname, String ipAddress, String dhcpServer,
                                String interfaceName, DeviceStatus status, boolean blocked, DeviceStatus statusBeforeBlock,
                                String leaseComment, SpeedLimit speedLimit, TrafficRate traffic, Instant lastSeenAt) {
            this.leaseId = leaseId;
            this.macAddress = macAddress;
            this.hostname = hostname;
            this.ipAddress = ipAddress;
            this.dhcpServer = dhcpServer;
            this.interfaceName = interfaceName;
            this.status = status;
            this.blocked = blocked;
            this.statusBeforeBlock = statusBeforeBlock;
            this.leaseComment = leaseComment;
            this.speedLimit = speedLimit;
            this.traffic = traffic;
            this.lastSeenAt = lastSeenAt;
        }

        private RouterDevice toDevice() {
            return new RouterDevice(leaseId, macAddress, hostname, ipAddress, dhcpServer, interfaceName,
                    blocked ? DeviceStatus.BLOCKED : status, blocked, leaseComment, speedLimit, traffic, lastSeenAt);
        }
    }
}
