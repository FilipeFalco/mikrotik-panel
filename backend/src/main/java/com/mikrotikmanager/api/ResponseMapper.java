package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.AuditLogResponse;
import com.mikrotikmanager.api.dto.DeviceResponse;
import com.mikrotikmanager.api.dto.PortResponse;
import com.mikrotikmanager.api.dto.SystemStatusResponse;
import com.mikrotikmanager.domain.AuditLog;
import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.service.DeviceView;
import com.mikrotikmanager.service.PortView;

import java.util.List;

final class ResponseMapper {
    private ResponseMapper() {
    }

    static SystemStatusResponse systemStatus(GatewayConnectionStatus status) {
        return systemStatus(status, false);
    }

    static SystemStatusResponse systemStatus(GatewayConnectionStatus status, boolean deviceBlockExecutionEnabled) {
        return new SystemStatusResponse(status.connected(), status.mockMode(), status.host(), status.port(),
                status.routerOsVersion(), status.latencyMillis(), status.message(), status.readOnly(), status.fastTrackDetected(),
                deviceBlockExecutionEnabled, false);
    }

    static SystemStatusResponse systemStatus(GatewayConnectionStatus status, MikrotikProperties properties) {
        boolean enabled = status.mockMode() || (status.connected() && properties.writeEnabled()
                && properties.deviceBlockWritesEnabled() && properties.writeCredentialsConfigured());
        boolean bandwidth = status.mockMode() || (status.connected() && properties.writeEnabled()
                && properties.bandwidthWritesEnabled() && properties.writeCredentialsConfigured());
        return new SystemStatusResponse(status.connected(), status.mockMode(), status.host(), status.port(), status.routerOsVersion(),
                status.latencyMillis(), status.message(), status.readOnly(), status.fastTrackDetected(), enabled, bandwidth);
    }

    static DeviceResponse device(DeviceView view) {
        var device = view.routerDevice();
        var metadata = view.metadata();
        var port = view.managedPort();
        return new DeviceResponse(
                view.displayName(),
                metadata == null ? null : metadata.friendlyName(),
                device.hostname(), device.ipAddress(), device.macAddress(), device.interfaceName(),
                port == null ? device.interfaceName() : port.friendlyName(), device.dhcpServer(),
                device.blocked() ? DeviceStatus.BLOCKED : device.status(), device.blocked(), device.leaseComment(),
                metadata == null ? null : metadata.notes(), device.speedLimit().downloadBps(), device.speedLimit().uploadBps(),
                device.traffic().downloadBps(), device.traffic().uploadBps(), device.lastSeenAt(),
                view.blockObservation() == null ? null : view.blockObservation().ownership().name(),
                view.blockObservation() == null ? null : view.blockObservation().source());
    }

    static PortResponse port(PortView view) {
        var routerInterface = view.routerInterface();
        var configuration = view.managedPort();
        List<DeviceResponse> devices = view.devices().stream().map(ResponseMapper::device).toList();
        int online = (int) view.devices().stream()
                .filter(device -> !device.routerDevice().blocked() && device.routerDevice().status() == DeviceStatus.ONLINE)
                .count();
        int blocked = (int) view.devices().stream().filter(device -> device.routerDevice().blocked()).count();
        return new PortResponse(
                routerInterface.name(), view.friendlyName(), configuration == null ? null : configuration.description(),
                configuration == null ? null : configuration.network(), configuration == null ? null : configuration.dhcpServer(),
                configuration != null, configuration == null ? null : configuration.role(), view.enabled(),
                routerInterface.running(), routerInterface.disabled(), devices.size(), online, blocked,
                view.speedLimit().downloadBps(), view.speedLimit().uploadBps(), routerInterface.traffic().downloadBps(),
                routerInterface.traffic().uploadBps(), devices);
    }

    static AuditLogResponse audit(AuditLog log) {
        return new AuditLogResponse(log.id(), log.createdAt(), log.action(), log.targetType(), log.targetIdentifier(),
                log.previousValue(), log.newValue(), log.success(), log.errorMessage());
    }
}
