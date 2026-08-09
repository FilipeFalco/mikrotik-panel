package com.mikrotikmanager.api.dto;

import java.util.List;

public record PortResponse(
        String interfaceName,
        String friendlyName,
        String description,
        String network,
        String dhcpServer,
        boolean managed,
        boolean enabled,
        boolean running,
        boolean disabled,
        int deviceCount,
        int onlineDeviceCount,
        int blockedDeviceCount,
        long downloadLimitBps,
        long uploadLimitBps,
        long downloadTrafficBps,
        long uploadTrafficBps,
        List<DeviceResponse> devices
) {
}
