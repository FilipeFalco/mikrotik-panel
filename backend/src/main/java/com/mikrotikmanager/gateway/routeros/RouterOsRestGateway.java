package com.mikrotikmanager.gateway.routeros;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.GatewayDiagnosticCheck;
import com.mikrotikmanager.domain.GatewayDiagnostics;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.GatewayErrorType;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.MikrotikDiagnosticsGateway;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.MikrotikGatewayException;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsDhcpLeaseDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsDhcpServerDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsFirewallFilterDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSimpleQueueDto;
import com.mikrotikmanager.gateway.routeros.dto.RouterOsSystemResourceDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Real RouterOS implementation for Phase 2.
 *
 * <p>Every RouterOS operation in this class delegates to the GET-only
 * {@link RouterOsRestClient}. Its mutation methods deliberately throw before
 * reaching the client, even if {@code mikrotik.write-enabled=true}: RouterOS
 * writes are not implemented in this phase.
 */
public final class RouterOsRestGateway implements MikrotikGateway, MikrotikDiagnosticsGateway {
    private final MikrotikProperties properties;
    private final RouterOsRestClient restClient;
    private final RouterOsDhcpMapper dhcpMapper;

    public RouterOsRestGateway(MikrotikProperties properties) {
        this(properties, new RouterOsRestClient(properties), new RouterOsDhcpMapper());
    }

    RouterOsRestGateway(MikrotikProperties properties, RouterOsRestClient restClient, RouterOsDhcpMapper dhcpMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.dhcpMapper = Objects.requireNonNull(dhcpMapper, "dhcpMapper");
    }

    @Override
    public GatewayConnectionStatus connectionStatus() {
        long startedAt = System.nanoTime();
        try {
            String version = readSystemVersion();
            return new GatewayConnectionStatus(
                    true,
                    false,
                    properties.host(),
                    properties.port(),
                    version,
                    elapsedMillis(startedAt),
                    "Conectado ao RouterOS em modo somente leitura.",
                    true,
                    false
            );
        } catch (RuntimeException exception) {
            MikrotikGatewayException failure = toGatewayException(exception);
            return new GatewayConnectionStatus(
                    false,
                    false,
                    properties.host(),
                    properties.port(),
                    null,
                    elapsedMillis(startedAt),
                    connectionMessage(failure.errorType()),
                    true,
                    false
            );
        }
    }

    @Override
    public List<RouterInterface> listInterfaces() {
        return read(() -> restClient.getInterfaces().stream()
                .map(RouterOsMapper::toRouterInterface)
                .toList());
    }

    @Override
    public List<RouterDevice> listDevices() {
        return read(() -> dhcpMapper.toRouterDevices(
                restClient.getDhcpServers(RouterOsDhcpServerDto.class),
                restClient.getDhcpLeases(RouterOsDhcpLeaseDto.class)
        ));
    }

    @Override
    public Map<String, SpeedLimit> listPortSpeeds() {
        return read(() -> RouterOsSimpleQueueMapper.toOwnedPortSpeeds(
                restClient.getSimpleQueues(RouterOsSimpleQueueDto.class)
        ));
    }

    @Override
    public Optional<RouterDevice> findDevice(String macAddress) {
        String normalizedMac = ManagedResourceIdentifier.normalizeMac(macAddress);
        return listDevices().stream()
                .filter(device -> normalizedMac.equals(device.macAddress()))
                .findFirst();
    }

    @Override
    public SpeedLimit getPortSpeed(String interfaceName) {
        return listPortSpeeds().getOrDefault(interfaceName, SpeedLimit.UNLIMITED);
    }

    @Override
    public void setPortSpeed(String interfaceName, SpeedLimit speedLimit) {
        throw writesNotImplemented();
    }

    @Override
    public void setDeviceSpeed(String macAddress, SpeedLimit speedLimit) {
        throw writesNotImplemented();
    }

    @Override
    public void blockDevice(String macAddress) {
        throw writesNotImplemented();
    }

    @Override
    public void unblockDevice(String macAddress) {
        throw writesNotImplemented();
    }

    /**
     * Diagnostics are intentionally on demand. In particular, firewall rules
     * are never read by {@link #connectionStatus()}, which is polled often.
     */
    @Override
    public GatewayDiagnostics diagnostics() {
        GatewayConnectionStatus status = connectionStatus();
        List<GatewayDiagnosticCheck> checks = new ArrayList<>();
        checks.add(new GatewayDiagnosticCheck("REST API", status.connected(), status.message()));
        if (!status.connected()) {
            checks.add(unchecked("Interfaces"));
            checks.add(unchecked("DHCP"));
            checks.add(unchecked("Queues"));
            checks.add(unchecked("FastTrack"));
            return new GatewayDiagnostics(status, false, checks);
        }

        checks.add(check("Interfaces", () -> {
            int count = listInterfaces().size();
            return count + " interface(s) lida(s).";
        }));
        checks.add(check("DHCP", () -> {
            List<RouterOsDhcpServerDto> servers = restClient.getDhcpServers(RouterOsDhcpServerDto.class);
            List<RouterOsDhcpLeaseDto> leases = restClient.getDhcpLeases(RouterOsDhcpLeaseDto.class);
            int correlatedDevices = dhcpMapper.toRouterDevices(servers, leases).size();
            return servers.size() + " server(s), " + leases.size() + " lease(s), "
                    + correlatedDevices + " dispositivo(s) correlacionado(s).";
        }));
        checks.add(check("Queues", () -> {
            List<RouterOsSimpleQueueDto> queues = restClient.getSimpleQueues(RouterOsSimpleQueueDto.class);
            int managedPortQueues = RouterOsSimpleQueueMapper.toOwnedPortSpeeds(queues).size();
            if (queues.isEmpty()) {
                return "Nenhuma Simple Queue configurada.";
            }
            return queues.size() + " queue(s) lida(s), " + managedPortQueues + " gerenciada(s) pelo painel.";
        }));

        FastTrackCheck fastTrack = fastTrackCheck();
        checks.add(fastTrack.check());
        return new GatewayDiagnostics(status, fastTrack.detected(), checks);
    }

    private String readSystemVersion() {
        return read(() -> {
            List<RouterOsSystemResourceDto> resources = restClient.getSystemResources();
            if (resources.size() != 1 || resources.getFirst() == null) {
                throw badResponse();
            }
            return RouterOsMapper.routerOsVersion(resources.getFirst());
        });
    }

    private FastTrackCheck fastTrackCheck() {
        try {
            List<RouterOsFirewallFilterDto> filters = restClient.getFirewallFilters(RouterOsFirewallFilterDto.class);
            List<RouterOsFastTrackRule> activeRules = RouterOsFastTrackDetector.findActiveRules(filters);
            if (activeRules.isEmpty()) {
                return new FastTrackCheck(false, new GatewayDiagnosticCheck(
                        "FastTrack", true, "Nenhuma regra FastTrack ativa foi encontrada."
                ));
            }
            return new FastTrackCheck(true, new GatewayDiagnosticCheck(
                    "FastTrack", true, activeRules.size() + " regra(s) FastTrack ativa(s) detectada(s)."
            ));
        } catch (RuntimeException exception) {
            MikrotikGatewayException failure = toGatewayException(exception);
            return new FastTrackCheck(false, new GatewayDiagnosticCheck(
                    "FastTrack", false, diagnosticMessage(failure.errorType())
            ));
        }
    }

    private GatewayDiagnosticCheck check(String name, Supplier<String> operation) {
        try {
            return new GatewayDiagnosticCheck(name, true, operation.get());
        } catch (RuntimeException exception) {
            MikrotikGatewayException failure = toGatewayException(exception);
            return new GatewayDiagnosticCheck(name, false, diagnosticMessage(failure.errorType()));
        }
    }

    private GatewayDiagnosticCheck unchecked(String name) {
        return new GatewayDiagnosticCheck(name, false, "Não verificado porque a REST API está indisponível.");
    }

    private <T> T read(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RouterOsRestClientException exception) {
            throw toGatewayException(exception);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw badResponse();
        }
    }

    private MikrotikGatewayException toGatewayException(RuntimeException exception) {
        if (exception instanceof MikrotikGatewayException gatewayException) {
            return gatewayException;
        }
        if (exception instanceof RouterOsRestClientException clientException) {
            return switch (clientException.errorType()) {
                case AUTHENTICATION_FAILED -> new MikrotikGatewayException(
                        GatewayErrorType.AUTHENTICATION_FAILED, "RouterOS authentication failed."
                );
                case TLS_ERROR -> new MikrotikGatewayException(
                        GatewayErrorType.TLS_ERROR, "RouterOS TLS verification failed."
                );
                case BAD_RESPONSE -> badResponse();
                case UNAVAILABLE -> new MikrotikGatewayException(
                        GatewayErrorType.UNAVAILABLE, "RouterOS is unavailable."
                );
            };
        }
        return badResponse();
    }

    private MikrotikGatewayException badResponse() {
        return new MikrotikGatewayException(GatewayErrorType.BAD_RESPONSE, "RouterOS returned an unexpected response.");
    }

    private MikrotikGatewayException writesNotImplemented() {
        return new MikrotikGatewayException(
                GatewayErrorType.WRITE_NOT_IMPLEMENTED,
                "RouterOS write operation is not implemented in Phase 2."
        );
    }

    private String connectionMessage(GatewayErrorType errorType) {
        return switch (errorType) {
            case AUTHENTICATION_FAILED -> "Autenticação no MikroTik falhou ou o usuário não possui permissão de leitura.";
            case TLS_ERROR -> "Não foi possível validar a conexão TLS com o MikroTik.";
            case BAD_RESPONSE -> "O MikroTik retornou uma resposta de leitura inesperada.";
            case WRITE_NOT_IMPLEMENTED -> "A escrita RouterOS não é implementada na Fase 2.";
            case UNAVAILABLE -> "Não foi possível comunicar com o MikroTik.";
        };
    }

    private String diagnosticMessage(GatewayErrorType errorType) {
        return switch (errorType) {
            case AUTHENTICATION_FAILED -> "Falha de autenticação ou de permissão de leitura.";
            case TLS_ERROR -> "Falha na validação TLS.";
            case BAD_RESPONSE -> "Resposta de leitura inesperada.";
            case WRITE_NOT_IMPLEMENTED -> "Não aplicável em diagnóstico.";
            case UNAVAILABLE -> "Requisição de leitura indisponível.";
        };
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record FastTrackCheck(boolean detected, GatewayDiagnosticCheck check) {
    }
}
