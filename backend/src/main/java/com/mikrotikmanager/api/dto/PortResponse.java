package com.mikrotikmanager.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mikrotikmanager.domain.ManagedPortRole;

import java.util.List;

public record PortResponse(
        String interfaceName,
        String friendlyName,
        String description,
        String network,
        String dhcpServer,
        boolean managed,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        ManagedPortRole role,
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
