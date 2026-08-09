package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedPort;
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

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public PortService(MikrotikGateway gateway, ManagedPortRepository managedPortRepository, DeviceService deviceService,
                       OperationLockManager lockManager, AuditService auditService) {
        this.gateway = gateway;
        this.managedPortRepository = managedPortRepository;
        this.deviceService = deviceService;
        this.lockManager = lockManager;
        this.auditService = auditService;
    }

    public List<PortView> listPorts() {
        Map<String, ManagedPort> configurations = managedPortRepository.findAll().stream()
                .collect(Collectors.toMap(ManagedPort::interfaceName, Function.identity()));
        return gateway.listInterfaces().stream()
                .filter(routerInterface -> "ether".equalsIgnoreCase(routerInterface.type()))
                .map(routerInterface -> toView(routerInterface, configurations.get(routerInterface.name())))
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
        if (!CidrValidator.isValid(network)) {
            throw new ApiException(ApiErrorCode.INVALID_INPUT, HttpStatus.BAD_REQUEST,
                    "A rede deve estar no formato CIDR, por exemplo 10.10.10.0/24.");
        }
        ensureInterfaceExists(interfaceName);
        return lockManager.withLock("port:" + interfaceName, () -> managedPortRepository.save(new ManagedPort(
                0, interfaceName, friendlyName.trim(), description, network, dhcpServer, enabled, Instant.now(), Instant.now()
        )));
    }

    public PortView setSpeed(String interfaceName, SpeedLimit requestedLimit) {
        validateSpeed(requestedLimit);
        return lockManager.withLock("port:" + interfaceName, () -> {
            ensureInterfaceExists(interfaceName);
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

    private PortView toView(RouterInterface routerInterface, ManagedPort configuration) {
        List<DeviceView> devices = deviceService.listByPort(routerInterface.name());
        return new PortView(routerInterface, configuration, gateway.getPortSpeed(routerInterface.name()), devices);
    }

    private void ensureInterfaceExists(String interfaceName) {
        boolean exists = gateway.listInterfaces().stream().map(RouterInterface::name).anyMatch(interfaceName::equals);
        if (!exists) {
            throw new ApiException(ApiErrorCode.PORT_NOT_FOUND, HttpStatus.NOT_FOUND, "Porta não encontrada no MikroTik.");
        }
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
