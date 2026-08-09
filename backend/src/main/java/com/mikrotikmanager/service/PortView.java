package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;

import java.util.List;

public record PortView(
        RouterInterface routerInterface,
        ManagedPort managedPort,
        SpeedLimit speedLimit,
        List<DeviceView> devices
) {
    public String friendlyName() {
        if (managedPort != null && managedPort.friendlyName() != null && !managedPort.friendlyName().isBlank()) {
            return managedPort.friendlyName();
        }
        return routerInterface.name();
    }

    public boolean enabled() {
        return managedPort != null && managedPort.enabled();
    }
}
