package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.DeviceBlockObservation;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Boundary to RouterOS. Implementations return application-owned domain models,
 * never raw RouterOS JSON. The real REST read implementation remains GET-only;
 * its legacy mutation-shaped methods deliberately fail before making an HTTP
 * request. Phase 4 writes use a separate narrow client.
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

    /**
     * Returns effective firewall/DHCP block observations in one batch. The
     * default keeps non-RouterOS test gateways compatible and performs no
     * additional per-device work.
     */
    default Map<String, DeviceBlockObservation> listDeviceBlockStates() {
        return Map.of();
    }

    Optional<RouterDevice> findDevice(String macAddress);

    SpeedLimit getPortSpeed(String interfaceName);

    void setPortSpeed(String interfaceName, SpeedLimit speedLimit);

    void setDeviceSpeed(String macAddress, SpeedLimit speedLimit);

    void blockDevice(String macAddress);

    void unblockDevice(String macAddress);
}
