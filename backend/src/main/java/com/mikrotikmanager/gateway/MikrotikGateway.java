package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Boundary to RouterOS. Implementations return application-owned domain models,
 * never raw RouterOS JSON. The real REST implementation remains GET-only in
 * Phase 3; its legacy mutation-shaped methods deliberately fail before making
 * an HTTP request.
 */
public interface MikrotikGateway {
    GatewayConnectionStatus connectionStatus();

    List<RouterInterface> listInterfaces();

    List<RouterDevice> listDevices();

    /**
     * Returns the configured speed limits for all interfaces in one gateway operation.
     * Interfaces absent from the result are treated as unlimited by callers.
     */
    Map<String, SpeedLimit> listPortSpeeds();

    Optional<RouterDevice> findDevice(String macAddress);

    SpeedLimit getPortSpeed(String interfaceName);

    void setPortSpeed(String interfaceName, SpeedLimit speedLimit);

    void setDeviceSpeed(String macAddress, SpeedLimit speedLimit);

    void blockDevice(String macAddress);

    void unblockDevice(String macAddress);
}
