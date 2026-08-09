package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import com.mikrotikmanager.support.ApiException;
import com.mikrotikmanager.support.ApiErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PortService {
    private static final Logger log = LoggerFactory.getLogger(PortService.class);

    private final MikrotikGateway gateway;
    private final ManagedPortRepository managedPortRepository;
    private final DeviceService deviceService;
    private final OperationLockManager lockManager;
    private final AuditService auditService;
    private final MikrotikWriteGuard writeGuard;

    public PortService(MikrotikGateway gateway, ManagedPortRepository managedPortRepository, DeviceService deviceService,
                       OperationLockManager lockManager, AuditService auditService, MikrotikWriteGuard writeGuard) {
        this.gateway = gateway;
        this.managedPortRepository = managedPortRepository;
        this.deviceService = deviceService;
        this.lockManager = lockManager;
        this.auditService = auditService;
        this.writeGuard = writeGuard;
    }

    public List<PortView> listPorts() {
        Map<String, ManagedPort> configurations = managedPortRepository.findAll().stream()
                .collect(Collectors.toMap(ManagedPort::interfaceName, Function.identity()));
        List<RouterInterface> interfaces = gateway.listInterfaces();
        Map<String, List<DeviceView>> devicesByInterface = deviceService.listDevices().stream()
                .collect(Collectors.groupingBy(device -> device.routerDevice().interfaceName()));
        Map<String, SpeedLimit> speedsByInterface = gateway.listPortSpeeds();

        return interfaces.stream()
                .filter(routerInterface -> "ether".equalsIgnoreCase(routerInterface.type()))
                .map(routerInterface -> toView(
                        routerInterface,
                        configurations.get(routerInterface.name()),
                        speedsByInterface.getOrDefault(routerInterface.name(), SpeedLimit.UNLIMITED),
                        devicesByInterface.getOrDefault(routerInterface.name(), List.of())))
                .sorted(Comparator.comparing(view -> view.routerInterface().name()))
                .toList();
    }

    public PortView getPort(String interfaceName) {
        return listPorts().stream()
                .filter(view -> view.routerInterface().name().equals(interfaceName))
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.PORT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Porta não encontrada no MikroTik."));
    }

    public ManagedPort updateConfiguration(String interfaceName, String friendlyName, String description, String network,
                                           String dhcpServer, boolean enabled) {
        return updateConfiguration(interfaceName, friendlyName, description, network, dhcpServer, enabled, null).managedPort();
    }

    /**
     * Persists local panel metadata for an interface. The only RouterOS access
     * is a GET-backed existence check; role changes do not change RouterOS.
     */
    @Transactional
    public PortView updateConfiguration(String interfaceName, String friendlyName, String description, String network,
                                        String dhcpServer, boolean enabled, ManagedPortRole requestedRole) {
        if (!CidrValidator.isValid(network)) {
            throw new ApiException(ApiErrorCode.INVALID_INPUT, HttpStatus.BAD_REQUEST,
                    "A rede deve estar no formato CIDR, por exemplo 10.10.10.0/24.");
        }
        String normalizedNetwork = CidrValidator.normalize(network);
        return lockManager.withLock("port:" + interfaceName, () -> {
            RouterInterface routerInterface = ensureInterfaceExists(interfaceName);
            ManagedPort existing = managedPortRepository.findByInterfaceName(interfaceName).orElse(null);
            ManagedPortRole effectiveRole = requestedRole != null
                    ? requestedRole
                    : existing == null ? ManagedPortRole.CLIENT : existing.role();
            ManagedPort requested = new ManagedPort(
                    0, interfaceName, friendlyName.trim(), description, normalizedNetwork, dhcpServer, effectiveRole,
                    enabled, Instant.now(), Instant.now()
            );
            try {
                ManagedPort saved = managedPortRepository.save(requested);
                auditService.record("PORT_CONFIGURATION_UPDATED", "PORT", interfaceName,
                        "configuration", "configuration", true, null);
                // Do not call listDevices() or listPortSpeeds() here: a local
                // PUT must not fan out into DHCP/queue RouterOS reads. The UI
                // refreshes the regular port listing after this response.
                return new PortView(routerInterface, saved, SpeedLimit.UNLIMITED, List.of());
            } catch (RuntimeException exception) {
                auditService.record("PORT_CONFIGURATION_UPDATED", "PORT", interfaceName,
                        "configuration", "configuration", false, safeMessage(exception));
                throw exception;
            }
        });
    }

    public PortView setSpeed(String interfaceName, SpeedLimit requestedLimit) {
        validateSpeed(requestedLimit);
        writeGuard.checkRouterWriteAllowed();
        return lockManager.withLock("port:" + interfaceName, () -> {
            ensureInterfaceExists(interfaceName);
            validateAgainstDevices(interfaceName, requestedLimit, gateway.listDevices());
            SpeedLimit previous = gateway.getPortSpeed(interfaceName);
            try {
                log.info("Updating port speed interface={} downloadBps={} uploadBps={}", interfaceName,
                        requestedLimit.downloadBps(), requestedLimit.uploadBps());
                gateway.setPortSpeed(interfaceName, requestedLimit);
                auditService.record("PORT_SPEED_CHANGED", "PORT", interfaceName, format(previous), format(requestedLimit), true, null);
                return getPort(interfaceName);
            } catch (RuntimeException exception) {
                auditService.record("PORT_SPEED_CHANGED", "PORT", interfaceName, format(previous), format(requestedLimit), false,
                        safeMessage(exception));
                throw exception;
            }
        });
    }

    private PortView toView(RouterInterface routerInterface, ManagedPort configuration, SpeedLimit speedLimit,
                            List<DeviceView> devices) {
        return new PortView(routerInterface, configuration, speedLimit, devices);
    }

    private void validateAgainstDevices(String interfaceName, SpeedLimit requestedLimit, List<RouterDevice> devices) {
        List<RouterDevice> exceedingDevices = devices.stream()
                .filter(device -> interfaceName.equals(device.interfaceName()))
                .filter(device -> exceedsPortLimit(device.speedLimit(), requestedLimit))
                .toList();
        if (exceedingDevices.isEmpty()) {
            return;
        }

        String deviceNames = exceedingDevices.stream()
                .map(this::displayName)
                .collect(Collectors.joining(", "));
        throw new ApiException(ApiErrorCode.INVALID_SPEED_LIMIT, HttpStatus.BAD_REQUEST,
                "O limite da porta não pode ser menor que o limite configurado para: " + deviceNames + ".");
    }

    private boolean exceedsPortLimit(SpeedLimit deviceLimit, SpeedLimit portLimit) {
        boolean exceedsDownload = portLimit.downloadBps() > 0
                && deviceLimit.downloadBps() > portLimit.downloadBps();
        boolean exceedsUpload = portLimit.uploadBps() > 0
                && deviceLimit.uploadBps() > portLimit.uploadBps();
        return exceedsDownload || exceedsUpload;
    }

    private String displayName(RouterDevice device) {
        return device.hostname() == null || device.hostname().isBlank() ? device.macAddress() : device.hostname();
    }

    private RouterInterface ensureInterfaceExists(String interfaceName) {
        return gateway.listInterfaces().stream()
                .filter(routerInterface -> interfaceName.equals(routerInterface.name()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.PORT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Porta não encontrada no MikroTik."));
    }

    private void validateSpeed(SpeedLimit speedLimit) {
        if (speedLimit.downloadBps() < 0 || speedLimit.uploadBps() < 0 ||
                speedLimit.downloadBps() > 10_000_000_000L || speedLimit.uploadBps() > 10_000_000_000L) {
            throw new ApiException(ApiErrorCode.INVALID_SPEED_LIMIT, HttpStatus.BAD_REQUEST,
                    "O limite de velocidade informado é inválido.");
        }
    }

    private String format(SpeedLimit limit) {
        return "download=" + limit.downloadBps() + "bps, upload=" + limit.uploadBps() + "bps";
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "Operação não concluída." : exception.getMessage();
    }
}
