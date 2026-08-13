package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.DeviceMetadata;
import com.mikrotikmanager.domain.DeviceBlockObservation;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.RouterDevice;

public record DeviceView(RouterDevice routerDevice, DeviceMetadata metadata, ManagedPort managedPort,
                         DeviceBlockObservation blockObservation) {
    public DeviceView(RouterDevice routerDevice, DeviceMetadata metadata, ManagedPort managedPort) {
        this(routerDevice, metadata, managedPort, null);
    }

    public String displayName() {
        if (metadata != null && metadata.friendlyName() != null && !metadata.friendlyName().isBlank()) {
            return metadata.friendlyName();
        }
        if (routerDevice.hostname() != null && !routerDevice.hostname().isBlank()) {
            return routerDevice.hostname();
        }
        return routerDevice.macAddress();
    }
}
