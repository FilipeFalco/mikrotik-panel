package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.DeviceMetadata;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.persistence.ManagedDeviceRepository;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import com.mikrotikmanager.support.ApiErrorCode;
import com.mikrotikmanager.support.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DeviceService {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final MikrotikGateway gateway;
    private final ManagedDeviceRepository managedDeviceRepository;
    private final ManagedPortRepository managedPortRepository;
    private final OperationLockManager lockManager;
    private final AuditService auditService;
    private final MikrotikWriteGuard writeGuard;

    public DeviceService(MikrotikGateway gateway, ManagedDeviceRepository managedDeviceRepository,
                         ManagedPortRepository managedPortRepository, OperationLockManager lockManager,
                         AuditService auditService, MikrotikWriteGuard writeGuard) {
        this.gateway = gateway;
        this.managedDeviceRepository = managedDeviceRepository;
        this.managedPortRepository = managedPortRepository;
        this.lockManager = lockManager;
        this.auditService = auditService;
        this.writeGuard = writeGuard;
    }

    public List<DeviceView> listDevices() {
        Map<String, ManagedPort> ports = managedPortRepository.findAll().stream()
                .collect(Collectors.toMap(ManagedPort::interfaceName, Function.identity()));
        return gateway.listDevices().stream()
                .map(device -> toView(device, ports.get(device.interfaceName())))
                .sorted(Comparator.comparing(DeviceView::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<DeviceView> listByPort(String interfaceName) {
        return listDevices().stream()
                .filter(view -> interfaceName.equals(view.routerDevice().interfaceName()))
                .toList();
    }

    public DeviceView getDevice(String macAddress) {
        String normalized = normalize(macAddress);
        RouterDevice routerDevice = gateway.findDevice(normalized)
                .orElseThrow(() -> new ApiException(ApiErrorCode.DEVICE_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Dispositivo não encontrado no MikroTik."));
        ManagedPort port = managedPortRepository.findByInterfaceName(routerDevice.interfaceName()).orElse(null);
        return toView(routerDevice, port);
    }

    public DeviceView updateMetadata(String macAddress, String friendlyName, String notes) {
        String normalized = normalize(macAddress);
        writeGuard.checkWriteAllowed();
        return lockManager.withLock("device:" + normalized, () -> {
            getDevice(normalized);
            try {
                managedDeviceRepository.save(normalized, friendlyName, notes);
                auditService.record("DEVICE_METADATA_UPDATED", "DEVICE", normalized,
                        "metadata", "metadata", true, null);
                return getDevice(normalized);
            } catch (RuntimeException exception) {
                auditService.record("DEVICE_METADATA_UPDATED", "DEVICE", normalized,
                        "metadata", "metadata", false, safeMessage(exception));
                throw exception;
            }
        });
    }

    public DeviceView setSpeed(String macAddress, SpeedLimit requestedLimit) {
        validateSpeed(requestedLimit);
        String normalized = normalize(macAddress);
        writeGuard.checkWriteAllowed();
        return lockManager.withLock("device:" + normalized, () -> {
            DeviceView device = getDevice(normalized);
            validateAgainstPort(device.routerDevice(), requestedLimit);
            SpeedLimit previous = device.routerDevice().speedLimit();
            try {
                log.info("Updating device speed mac={} downloadBps={} uploadBps={}", normalized,
                        requestedLimit.downloadBps(), requestedLimit.uploadBps());
                gateway.setDeviceSpeed(normalized, requestedLimit);
                auditService.record("DEVICE_SPEED_CHANGED", "DEVICE", normalized, format(previous), format(requestedLimit), true, null);
                return getDevice(normalized);
            } catch (RuntimeException exception) {
                auditService.record("DEVICE_SPEED_CHANGED", "DEVICE", normalized, format(previous), format(requestedLimit), false,
                        safeMessage(exception));
                throw exception;
            }
        });
    }

    public DeviceView block(String macAddress) {
        return changeBlockState(macAddress, true);
    }

    public DeviceView unblock(String macAddress) {
        return changeBlockState(macAddress, false);
    }

    private DeviceView changeBlockState(String macAddress, boolean block) {
        String normalized = normalize(macAddress);
        writeGuard.checkWriteAllowed();
        return lockManager.withLock("device:" + normalized, () -> {
            DeviceView device = getDevice(normalized);
            boolean previous = device.routerDevice().blocked();
            String action = block ? "DEVICE_BLOCKED" : "DEVICE_UNBLOCKED";
            try {
                log.info("{} device mac={} managedResource={}", block ? "Blocking" : "Unblocking", normalized,
                        ManagedResourceIdentifier.forDevice(normalized));
                if (block) {
                    gateway.blockDevice(normalized);
                } else {
                    gateway.unblockDevice(normalized);
                }
                auditService.record(action, "DEVICE", normalized, Boolean.toString(previous), Boolean.toString(block), true, null);
                log.info("Device block operation completed mac={} blocked={}", normalized, block);
                return getDevice(normalized);
            } catch (RuntimeException exception) {
                auditService.record(action, "DEVICE", normalized, Boolean.toString(previous), Boolean.toString(block), false,
                        safeMessage(exception));
                throw exception;
            }
        });
    }

    private DeviceView toView(RouterDevice routerDevice, ManagedPort port) {
        DeviceMetadata metadata = managedDeviceRepository.findByMacAddress(routerDevice.macAddress()).orElse(null);
        return new DeviceView(routerDevice, metadata, port);
    }

    private void validateAgainstPort(RouterDevice device, SpeedLimit requested) {
        SpeedLimit portLimit = gateway.getPortSpeed(device.interfaceName());
        boolean exceedsDownload = portLimit.downloadBps() > 0 && requested.downloadBps() > portLimit.downloadBps();
        boolean exceedsUpload = portLimit.uploadBps() > 0 && requested.uploadBps() > portLimit.uploadBps();
        if (exceedsDownload || exceedsUpload) {
            throw new ApiException(ApiErrorCode.INVALID_SPEED_LIMIT, HttpStatus.BAD_REQUEST,
                    "O limite do dispositivo não pode ultrapassar o limite total da porta.");
        }
    }

    private void validateSpeed(SpeedLimit speedLimit) {
        if (speedLimit.downloadBps() < 0 || speedLimit.uploadBps() < 0 ||
                speedLimit.downloadBps() > 10_000_000_000L || speedLimit.uploadBps() > 10_000_000_000L) {
            throw new ApiException(ApiErrorCode.INVALID_SPEED_LIMIT, HttpStatus.BAD_REQUEST,
                    "O limite de velocidade informado é inválido.");
        }
    }

    private String normalize(String macAddress) {
        try {
            return ManagedResourceIdentifier.normalizeMac(macAddress);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ApiErrorCode.INVALID_INPUT, HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private String format(SpeedLimit limit) {
        return "download=" + limit.downloadBps() + "bps, upload=" + limit.uploadBps() + "bps";
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "Operação não concluída." : exception.getMessage();
    }
}
