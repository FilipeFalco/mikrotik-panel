package com.mikrotikmanager.service;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.domain.PlanSeverity;
import com.mikrotikmanager.domain.ReadinessCheck;
import com.mikrotikmanager.domain.ReadinessSeverity;
import com.mikrotikmanager.domain.ReadinessSummary;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.ReconciliationStatus;
import com.mikrotikmanager.domain.WriteReadinessReport;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds an on-demand, read-only preflight report for the explicitly enabled
 * Phase 4 device-block write capability.
 *
 * <p>The service deliberately reuses the single snapshot captured by
 * {@link ReconciliationService}; it never requests a second snapshot, calls a
 * write guard, persists anything, or invokes a mutation operation. The one
 * extra gateway call is {@link MikrotikGateway#connectionStatus()}, which is
 * used only to avoid attempting reconciliation when RouterOS is unavailable.
 * </p>
 *
 * <p>Readiness counts only ports that are applicable candidates for future
 * bandwidth operations: a local CLIENT port that is enabled. WAN ports and
 * disabled CLIENT ports are legitimately {@code NOT_APPLICABLE} and never make
 * readiness invalid; only an enabled CLIENT port with a missing or invalid
 * CIDR blocks future execution.</p>
 */
@Service
public class WriteReadinessService {
    private static final Logger log = LoggerFactory.getLogger(WriteReadinessService.class);

    private final MikrotikProperties properties;
    private final MikrotikGateway gateway;
    private final ReconciliationService reconciliationService;
    private final ManagedPortRepository managedPortRepository;
    private final Clock clock;

    @Autowired
    public WriteReadinessService(MikrotikProperties properties, MikrotikGateway gateway,
                                 ReconciliationService reconciliationService, ManagedPortRepository managedPortRepository) {
        this(properties, gateway, reconciliationService, managedPortRepository, Clock.systemUTC());
    }

    WriteReadinessService(MikrotikProperties properties, MikrotikGateway gateway,
                          ReconciliationService reconciliationService, ManagedPortRepository managedPortRepository,
                          Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService");
        this.managedPortRepository = Objects.requireNonNull(managedPortRepository, "managedPortRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Produces an observational report. It never makes RouterOS executable in
     * Phase 4 writes are never performed by this diagnostic service, including
     * when both write flags are enabled.
     */
    public WriteReadinessReport analyze() {
        GatewayConnectionStatus connection = connectionStatus();
        if (connection == null || !connection.connected()) {
            return unavailableReport(connection);
        }

        try {
            ReconciliationReport reconciliation = reconciliationService.analyze();
            WriteReadinessReport report = reportFrom(connection, reconciliation);
            log.info("Write readiness completed managed={} conflicts={} drifted={} missing={} readyForFutureExecution={}",
                    report.summary().managed(), report.summary().conflicts(), report.summary().drifted(),
                    report.summary().missing(), report.readyForFutureExecution());
            return report;
        } catch (RuntimeException exception) {
            // Do not log or expose an exception message: transport failures can
            // contain sensitive request context in an implementation detail.
            log.warn("Write readiness could not obtain a read-only reconciliation snapshot.");
            return analysisUnavailableReport(connection);
        }
    }

    /**
     * Produces readiness from an already-captured reconciliation, reusing the
     * same RouterOS observation. Used by the combined write-analysis endpoint
     * so readiness and reconciliation are guaranteed to share one snapshot.
     * It never requests another snapshot or performs a RouterOS write.
     */
    public WriteReadinessReport analyze(GatewayConnectionStatus connection, ReconciliationReport reconciliation) {
        Objects.requireNonNull(connection, "connection");
        if (!connection.connected()) {
            return unavailableReport(connection);
        }
        if (reconciliation == null) {
            return analysisUnavailableReport(connection);
        }
        return reportFrom(connection, reconciliation);
    }

    private GatewayConnectionStatus connectionStatus() {
        try {
            return gateway.connectionStatus();
        } catch (RuntimeException exception) {
            // A readiness endpoint is a diagnostic surface. It should return a
            // structured unavailable report rather than transport exception text.
            log.warn("Write readiness could not determine RouterOS connection status.");
            return null;
        }
    }

    private WriteReadinessReport reportFrom(GatewayConnectionStatus connection,
                                             ReconciliationReport reconciliation) {
        List<ManagedPort> localPorts = managedPortRepository.findAll();
        ReadinessSummary summary = summary(reconciliation, localPorts);
        int localManagedPorts = summary.managedPorts();
        int validManagedPorts = summary.validManagedPorts();
        boolean hasBlockingDrift = reconciliation.resources().stream()
                .filter(resource -> resource.status() == ReconciliationStatus.DRIFTED)
                .flatMap(resource -> resource.findings().stream())
                .anyMatch(finding -> finding.severity() == PlanSeverity.BLOCKING);

        List<ReadinessCheck> checks = new ArrayList<>();
        checks.add(check("ROUTEROS_CONNECTED", "RouterOS conectado", true, ReadinessSeverity.INFO,
                "A conexão de leitura com RouterOS foi confirmada."));
        checks.add(modeCheck(connection));
        checks.add(writeFlagCheck());
        checks.add(deviceBlockWriteFlagCheck());
        checks.add(writeCredentialsCheck(connection));
        checks.add(check("BLOCKING_STRATEGY", "Estratégia de bloqueio", true, ReadinessSeverity.INFO,
                "FIREWALL_MAC_RULE: uma regra drop forward por MAC com comentário MTMGR exato."));
        checks.add(check("FIREWALL_ORDERING_ANALYZABLE", "Ordem do firewall", true, ReadinessSeverity.INFO,
                "A lista /ip/firewall/filter será relida e a posição será comprovada após cada escrita."));
        checks.add(check("INTERFACES_READABLE", "Interfaces", reconciliation.observedInterfaceCount() > 0,
                reconciliation.observedInterfaceCount() > 0 ? ReadinessSeverity.INFO : ReadinessSeverity.BLOCKING,
                reconciliation.observedInterfaceCount() > 0
                        ? reconciliation.observedInterfaceCount() + " interface(s) observada(s) no snapshot."
                        : "Nenhuma interface foi observada; a futura execução não pode identificar portas com segurança."));
        checks.add(check("DHCP_READABLE", "DHCP", true, ReadinessSeverity.INFO,
                reconciliation.observedDhcpServerCount() + " servidor(es) DHCP e "
                        + reconciliation.observedLeaseCount() + " lease(s) observados no snapshot."));
        checks.add(check("QUEUES_READABLE", "Simple Queues", true, ReadinessSeverity.INFO,
                reconciliation.observedSimpleQueueCount() + " Simple Queue(s) observada(s) no snapshot."));
        checks.add(check("OWNERSHIP_ANALYZABLE", "Ownership", true, ReadinessSeverity.INFO,
                "Ownership foi analisado somente por identificadores MTMGR exatos."));
        checks.add(check("FASTTRACK_STATUS_KNOWN", "FastTrack", true, ReadinessSeverity.INFO,
                "O status de FastTrack foi lido no snapshot de firewall."));
        checks.add(fastTrackBandwidthCheck(reconciliation.fastTrackDetected()));
        checks.add(managedPortsCheck(localManagedPorts, validManagedPorts));
        checks.add(conflictCheck(reconciliation));
        checks.add(ambiguousOwnershipCheck(reconciliation));
        checks.add(driftCheck(reconciliation, hasBlockingDrift));
        checks.add(check("RECONCILIATION_SUMMARY", "Reconciliação", true, ReadinessSeverity.INFO,
                reconciliationSummaryDetail(reconciliation)));

        boolean executionEnabled = connection.mockMode() || (properties.writeEnabled()
                && properties.deviceBlockWritesEnabled() && properties.writeCredentialsConfigured());
        boolean readyForFutureExecution = executionEnabled && !hasBlockingFinding(checks);
        boolean bandwidthExecutionEnabled = connection.mockMode() || (properties.writeEnabled() && properties.bandwidthWritesEnabled()
                && properties.writeCredentialsConfigured() && !reconciliation.fastTrackDetected() && !hasBlockingDrift);
        return new WriteReadinessReport(Instant.now(clock), connection.mockMode(), properties.writeEnabled(),
                readyForFutureExecution, executionEnabled, WriteReadinessReport.PHASE_4_DEVICE_BLOCK_NOTICE, checks, summary,
                properties.deviceBlockWritesEnabled(), properties.writeCredentialsConfigured(), "FIREWALL_MAC_RULE", true,
                properties.bandwidthWritesEnabled(), bandwidthExecutionEnabled);
    }

    private WriteReadinessReport unavailableReport(GatewayConnectionStatus connection) {
        boolean mockMode = connection != null && connection.mockMode();
        List<ReadinessCheck> checks = unavailableChecks(mockMode,
                "RouterOS não está conectado; a análise observacional não foi executada.");
        return new WriteReadinessReport(Instant.now(clock), mockMode, properties.writeEnabled(), false, false,
                WriteReadinessReport.PHASE_4_DEVICE_BLOCK_NOTICE, checks, emptySummary(),
                properties.deviceBlockWritesEnabled(), properties.writeCredentialsConfigured(), "FIREWALL_MAC_RULE", false,
                properties.bandwidthWritesEnabled(), false);
    }

    private WriteReadinessReport analysisUnavailableReport(GatewayConnectionStatus connection) {
        List<ReadinessCheck> checks = unavailableChecks(connection.mockMode(),
                "O snapshot de leitura não pôde ser concluído; nenhum estado RouterOS foi modificado.");
        checks.set(0, check("ROUTEROS_CONNECTED", "RouterOS conectado", true, ReadinessSeverity.INFO,
                "A conexão de leitura foi confirmada, mas a análise não pôde obter um snapshot completo."));
        return new WriteReadinessReport(Instant.now(clock), connection.mockMode(), properties.writeEnabled(), false, false,
                WriteReadinessReport.PHASE_4_DEVICE_BLOCK_NOTICE, checks, emptySummary(),
                properties.deviceBlockWritesEnabled(), properties.writeCredentialsConfigured(), "FIREWALL_MAC_RULE", false,
                properties.bandwidthWritesEnabled(), false);
    }

    private List<ReadinessCheck> unavailableChecks(boolean mockMode, String connectionDetail) {
        List<ReadinessCheck> checks = new ArrayList<>();
        checks.add(check("ROUTEROS_CONNECTED", "RouterOS conectado", false, ReadinessSeverity.BLOCKING, connectionDetail));
        checks.add(modeCheck(mockMode));
        checks.add(writeFlagCheck());
        checks.add(deviceBlockWriteFlagCheck());
        checks.add(check("BLOCKING_STRATEGY", "Estratégia de bloqueio", true, ReadinessSeverity.INFO,
                "FIREWALL_MAC_RULE: uma regra drop forward por MAC com comentário MTMGR exato."));
        checks.add(check("FIREWALL_ORDERING_ANALYZABLE", "Ordem do firewall", false, ReadinessSeverity.BLOCKING,
                "A ordem do firewall não foi analisada porque o snapshot RouterOS está indisponível."));
        checks.add(check("WRITE_CREDENTIALS_CONFIGURED", "Credenciais de escrita", mockMode || properties.writeCredentialsConfigured(),
                mockMode || properties.writeCredentialsConfigured() ? ReadinessSeverity.INFO : ReadinessSeverity.BLOCKING,
                mockMode ? "Mock mode não exige credenciais de escrita."
                        : properties.writeCredentialsConfigured() ? "Credenciais separadas de escrita configuradas."
                        : "As credenciais de escrita são obrigatórias e não usam fallback das credenciais de leitura."));
        checks.add(unavailableCheck("INTERFACES_READABLE", "Interfaces"));
        checks.add(unavailableCheck("DHCP_READABLE", "DHCP"));
        checks.add(unavailableCheck("QUEUES_READABLE", "Simple Queues"));
        checks.add(unavailableCheck("OWNERSHIP_ANALYZABLE", "Ownership"));
        checks.add(unavailableCheck("FASTTRACK_STATUS_KNOWN", "FastTrack"));
        checks.add(unavailableCheck("MANAGED_PORTS_VALID", "Portas gerenciadas"));
        checks.add(unavailableCheck("NO_FOREIGN_CONFLICT", "Conflitos foreign"));
        checks.add(unavailableCheck("NO_AMBIGUOUS_OWNERSHIP", "Ownership ambíguo"));
        return checks;
    }

    private ReadinessCheck modeCheck(GatewayConnectionStatus connection) {
        return modeCheck(connection.mockMode());
    }

    private ReadinessCheck modeCheck(boolean mockMode) {
        return check("REAL_ROUTER_MODE", "Modo real", !mockMode,
                mockMode ? ReadinessSeverity.WARNING : ReadinessSeverity.INFO,
                mockMode
                        ? "Modo simulado ativo; este diagnóstico não confirma o estado de um RouterOS físico."
                        : "Modo real ativo; o diagnóstico usa apenas leituras RouterOS.");
    }

    private ReadinessCheck writeFlagCheck() {
        boolean disabled = !properties.writeEnabled();
        return check("WRITE_FLAG_DISABLED", "Write flag", disabled,
                disabled ? ReadinessSeverity.INFO : ReadinessSeverity.WARNING,
                disabled
                        ? "MIKROTIK_WRITE_ENABLED permanece desabilitada."
                        : "MIKROTIK_WRITE_ENABLED está ativa; a execução ainda exige a segunda flag e credenciais separadas.");
    }

    private ReadinessCheck deviceBlockWriteFlagCheck() {
        boolean enabled = properties.deviceBlockWritesEnabled();
        return check("DEVICE_BLOCK_WRITE_FLAG", "Write flag de bloqueio", enabled,
                enabled ? ReadinessSeverity.INFO : ReadinessSeverity.WARNING,
                enabled ? "MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED está ativa para FIREWALL_MAC_RULE."
                        : "MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED permanece desabilitada.");
    }

    private ReadinessCheck writeCredentialsCheck(GatewayConnectionStatus connection) {
        boolean configured = connection.mockMode() || properties.writeCredentialsConfigured();
        return check("WRITE_CREDENTIALS_CONFIGURED", "Credenciais de escrita", configured,
                configured ? ReadinessSeverity.INFO : ReadinessSeverity.BLOCKING,
                configured ? (connection.mockMode() ? "Mock mode não exige credenciais de escrita."
                        : "Credenciais separadas de escrita configuradas.")
                        : "As credenciais de escrita são obrigatórias e não usam fallback das credenciais de leitura.");
    }

    private ReadinessCheck fastTrackBandwidthCheck(boolean detected) {
        return check("FASTTRACK_BANDWIDTH_WARNING", "Impacto do FastTrack em banda", !detected,
                detected ? ReadinessSeverity.WARNING : ReadinessSeverity.INFO,
                detected
                        ? "FastTrack ativo pode impedir que futuras Simple Queues tratem parte do tráfego. Nenhuma regra foi alterada."
                        : "FastTrack não foi detectado no snapshot de firewall.");
    }

    private ReadinessCheck managedPortsCheck(int managedPorts, int validManagedPorts) {
        if (managedPorts == 0) {
            return check("MANAGED_PORTS_VALID", "Portas gerenciadas", true, ReadinessSeverity.INFO,
                    "Nenhuma porta CLIENT habilitada foi configurada para análise.");
        }
        boolean valid = managedPorts == validManagedPorts;
        return check("MANAGED_PORTS_VALID", "Portas gerenciadas", valid,
                valid ? ReadinessSeverity.INFO : ReadinessSeverity.BLOCKING,
                valid
                        ? validManagedPorts + " porta(s) CLIENT habilitada(s) com CIDR válido."
                        : validManagedPorts + " de " + managedPorts
                        + " porta(s) CLIENT habilitada(s) têm CIDR válido. WAN e portas desabilitadas não contam.");
    }

    private ReadinessCheck conflictCheck(ReconciliationReport reconciliation) {
        boolean noConflicts = reconciliation.summary().conflicts() == 0;
        return check("NO_FOREIGN_CONFLICT", "Conflitos foreign", noConflicts,
                noConflicts ? ReadinessSeverity.INFO : ReadinessSeverity.BLOCKING,
                noConflicts
                        ? "Nenhum recurso RouterOS manual conflitante foi encontrado."
                        : reconciliation.summary().conflicts()
                        + " conflito(s) RouterOS manual(is) ou sem ownership comprovado bloqueiam futura execução.");
    }

    private ReadinessCheck ambiguousOwnershipCheck(ReconciliationReport reconciliation) {
        boolean noAmbiguity = reconciliation.summary().ambiguous() == 0;
        return check("NO_AMBIGUOUS_OWNERSHIP", "Ownership ambíguo", noAmbiguity,
                noAmbiguity ? ReadinessSeverity.INFO : ReadinessSeverity.BLOCKING,
                noAmbiguity
                        ? "Nenhum recurso com ownership gerenciado duplicado foi encontrado."
                        : reconciliation.summary().ambiguous()
                        + " recurso(s) com ownership duplicado bloqueiam futura execução.");
    }

    private ReadinessCheck driftCheck(ReconciliationReport reconciliation, boolean hasBlockingDrift) {
        boolean noDrift = reconciliation.summary().drifted() == 0;
        ReadinessSeverity severity = noDrift ? ReadinessSeverity.INFO
                : hasBlockingDrift ? ReadinessSeverity.BLOCKING : ReadinessSeverity.WARNING;
        String detail = noDrift
                ? "Nenhum recurso gerenciado divergiu do estado local esperado."
                : reconciliation.summary().drifted() + " recurso(s) gerenciado(s) apresentam drift e não serão corrigidos automaticamente.";
        return check("MANAGED_RESOURCE_DRIFT", "Drift", noDrift, severity, detail);
    }

    private ReadinessCheck unavailableCheck(String code, String description) {
        return check(code, description, false, ReadinessSeverity.BLOCKING,
                "Não verificado porque o snapshot de leitura RouterOS não está disponível.");
    }

    private ReadinessCheck check(String code, String description, boolean satisfied, ReadinessSeverity severity, String detail) {
        return new ReadinessCheck(code, description, satisfied, severity, detail);
    }

    private ReadinessSummary summary(ReconciliationReport reconciliation, List<ManagedPort> localPorts) {
        // managedPorts counts only ports that are applicable candidates for
        // future bandwidth operations: a local CLIENT port that is enabled. WAN
        // ports and disabled CLIENT ports are legitimately NOT_APPLICABLE and
        // never count against readiness. validManagedPorts counts how many of
        // those applicable candidates have a parseable CIDR configured.
        int applicableClientPorts = (int) localPorts.stream()
                .filter(port -> port.enabled() && port.role() == ManagedPortRole.CLIENT)
                .count();
        int validApplicableClientPorts = (int) localPorts.stream()
                .filter(port -> port.enabled() && port.role() == ManagedPortRole.CLIENT)
                .filter(port -> port.network() != null && !port.network().isBlank())
                .filter(port -> CidrValidator.isValid(port.network()))
                .count();
        return new ReadinessSummary(reconciliation.summary().managed(), reconciliation.summary().foreign(),
                reconciliation.summary().inSync(), reconciliation.summary().drifted(), reconciliation.summary().missing(),
                reconciliation.summary().conflicts(), reconciliation.summary().ambiguous(),
                applicableClientPorts, validApplicableClientPorts);
    }

    private ReadinessSummary emptySummary() {
        return new ReadinessSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private boolean hasBlockingFinding(List<ReadinessCheck> checks) {
        return checks.stream().anyMatch(check -> !check.satisfied() && check.severity() == ReadinessSeverity.BLOCKING);
    }

    private String reconciliationSummaryDetail(ReconciliationReport reconciliation) {
        return "Managed: " + reconciliation.summary().managed()
                + ", foreign: " + reconciliation.summary().foreign()
                + ", in sync: " + reconciliation.summary().inSync()
                + ", drifted: " + reconciliation.summary().drifted()
                + ", missing: " + reconciliation.summary().missing()
                + ", conflicts: " + reconciliation.summary().conflicts()
                + ", ambiguous: " + reconciliation.summary().ambiguous() + ".";
    }
}
