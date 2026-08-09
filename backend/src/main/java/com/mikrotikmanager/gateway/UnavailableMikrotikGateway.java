package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Safe placeholder used until the read-only RouterOS REST adapter is delivered in Phase 2. */
public final class UnavailableMikrotikGateway implements MikrotikGateway {
    private final String host;
    private final int port;

    public UnavailableMikrotikGateway(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public GatewayConnectionStatus connectionStatus() {
        return new GatewayConnectionStatus(false, false, host, port, null, null,
                "A integração RouterOS ainda não foi habilitada nesta fase.", false);
    }

    @Override
    public List<RouterInterface> listInterfaces() {
        throw unavailable();
    }

    @Override
    public List<RouterDevice> listDevices() {
        throw unavailable();
    }

    @Override
    public Map<String, SpeedLimit> listPortSpeeds() {
        throw unavailable();
    }

    @Override
    public Optional<RouterDevice> findDevice(String macAddress) {
        throw unavailable();
    }

    @Override
    public SpeedLimit getPortSpeed(String interfaceName) {
        throw unavailable();
    }

    @Override
    public void setPortSpeed(String interfaceName, SpeedLimit speedLimit) {
        throw unavailable();
    }

    @Override
    public void setDeviceSpeed(String macAddress, SpeedLimit speedLimit) {
        throw unavailable();
    }

    @Override
    public void blockDevice(String macAddress) {
        throw unavailable();
    }

    @Override
    public void unblockDevice(String macAddress) {
        throw unavailable();
    }

    private MikrotikGatewayException unavailable() {
        return new MikrotikGatewayException("A integração RouterOS será disponibilizada na Fase 2.");
    }
}
