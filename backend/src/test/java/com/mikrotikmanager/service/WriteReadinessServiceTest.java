package com.mikrotikmanager.service;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.PlanSeverity;
import com.mikrotikmanager.domain.ReadinessCheck;
import com.mikrotikmanager.domain.ReadinessSeverity;
import com.mikrotikmanager.domain.ReconciliationFinding;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.ReconciliationResource;
import com.mikrotikmanager.domain.ReconciliationStatus;
import com.mikrotikmanager.domain.ReconciliationSummary;
import com.mikrotikmanager.domain.ResourceOwnership;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.WriteReadinessReport;
import com.mikrotikmanager.gateway.MikrotikGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WriteReadinessServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");

    @Test
    void reportsCleanRealReadinessButKeepsExecutionStructurallyDisabled() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        when(gateway.connectionStatus()).thenReturn(connected(false, "connection summary"));
        when(reconciliation.analyze()).thenReturn(report(false, cleanResource(),
                new ReconciliationSummary(1, 0, 1, 0, 0, 0, 0, 0)));

        WriteReadinessReport result = service(false, gateway, reconciliation).analyze();

        assertThat(result.readyForFutureExecution()).isTrue();
        assertThat(result.executionEnabled()).isFalse();
        assertThat(result.writeFlagEnabled()).isFalse();
        assertThat(result.generatedAt()).isEqualTo(NOW);
        assertThat(check(result, "WRITE_FLAG_DISABLED"))
                .extracting(ReadinessCheck::satisfied, ReadinessCheck::severity)
                .containsExactly(true, ReadinessSeverity.INFO);
        assertThat(check(result, "INTERFACES_READABLE").detail()).contains("3 interface(s)");
        assertThat(result.summary())
                .extracting(summary -> summary.managed(), summary -> summary.inSync(), summary -> summary.validManagedPorts())
                .containsExactly(1, 1, 1);

        verify(gateway).connectionStatus();
        verifyNoMoreInteractions(gateway);
        verify(reconciliation).analyze();
        verifyNoMoreInteractions(reconciliation);
        assertNoGatewayMutation(gateway);
    }

    @Test
    void fastTrackIsAWarningWithoutBlockingAnOtherwiseCleanFuturePreflight() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        when(gateway.connectionStatus()).thenReturn(connected(false, "safe"));
        when(reconciliation.analyze()).thenReturn(report(true, cleanResource(),
                new ReconciliationSummary(1, 0, 1, 0, 0, 0, 0, 0)));

        WriteReadinessReport result = service(false, gateway, reconciliation).analyze();

        assertThat(result.readyForFutureExecution()).isTrue();
        assertThat(check(result, "FASTTRACK_BANDWIDTH_WARNING"))
                .extracting(ReadinessCheck::satisfied, ReadinessCheck::severity, ReadinessCheck::detail)
                .containsExactly(false, ReadinessSeverity.WARNING,
                        "FastTrack ativo pode impedir que futuras Simple Queues tratem parte do tráfego. Nenhuma regra foi alterada.");
        assertThat(result.executionEnabled()).isFalse();
        assertNoGatewayMutation(gateway);
    }

    @Test
    void wanAndDisabledClientPortsDoNotBlockReadinessWhenAValidClientPortExists() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        when(gateway.connectionStatus()).thenReturn(connected(false, "safe"));
        ReconciliationResource wan = notApplicable("ether1");
        ReconciliationResource disabled = notApplicable("ether3");
        when(reconciliation.analyze()).thenReturn(new ReconciliationReport(NOW, "a".repeat(64), 3, 1, 2, 1,
                false, List.of(wan, cleanResource(), disabled), new ReconciliationSummary(1, 0, 1, 0, 0, 0, 0, 2)));

        WriteReadinessReport result = service(false, gateway, reconciliation, "very-secret-password",
                List.of(wanPort(), clientEnabledPort(), clientDisabledPort())).analyze();

        assertThat(result.readyForFutureExecution()).isTrue();
        assertThat(result.executionEnabled()).isFalse();
        assertThat(check(result, "MANAGED_PORTS_VALID"))
                .extracting(ReadinessCheck::satisfied, ReadinessCheck::severity)
                .containsExactly(true, ReadinessSeverity.INFO);
        assertThat(result.summary())
                .extracting(s -> s.managedPorts(), s -> s.validManagedPorts())
                .containsExactly(1, 1);
        assertNoGatewayMutation(gateway);
    }

    @Test
    void clientEnabledWithoutCidrBlocksReadinessEvenWhenWanIsAlsoPresent() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        when(gateway.connectionStatus()).thenReturn(connected(false, "safe"));
        ReconciliationResource wan = notApplicable("ether1");
        ReconciliationResource clientWithoutCidr = notApplicable("ether4");
        when(reconciliation.analyze()).thenReturn(new ReconciliationReport(NOW, "a".repeat(64), 3, 1, 2, 1,
                false, List.of(wan, clientWithoutCidr), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 2)));

        WriteReadinessReport result = service(false, gateway, reconciliation, "very-secret-password",
                List.of(wanPort(), clientEnabledPortWithoutCidr())).analyze();

        assertThat(result.readyForFutureExecution()).isFalse();
        assertThat(check(result, "MANAGED_PORTS_VALID"))
                .extracting(ReadinessCheck::satisfied, ReadinessCheck::severity)
                .containsExactly(false, ReadinessSeverity.BLOCKING);
        assertThat(result.summary())
                .extracting(s -> s.managedPorts(), s -> s.validManagedPorts())
                .containsExactly(1, 0);
        assertNoGatewayMutation(gateway);
    }

    @Test
    void clientEnabledWithInvalidCidrBlocksReadiness() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        when(gateway.connectionStatus()).thenReturn(connected(false, "safe"));
        ReconciliationResource invalidCidr = notApplicable("ether5");
        when(reconciliation.analyze()).thenReturn(new ReconciliationReport(NOW, "a".repeat(64), 3, 1, 2, 1,
                false, List.of(invalidCidr), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 1)));

        WriteReadinessReport result = service(false, gateway, reconciliation, "very-secret-password",
                List.of(clientEnabledPortWithInvalidCidr())).analyze();

        assertThat(result.readyForFutureExecution()).isFalse();
        assertThat(check(result, "MANAGED_PORTS_VALID"))
                .extracting(ReadinessCheck::satisfied, ReadinessCheck::severity)
                .containsExactly(false, ReadinessSeverity.BLOCKING);
        assertThat(result.summary())
                .extracting(s -> s.managedPorts(), s -> s.validManagedPorts())
                .containsExactly(1, 0);
        assertNoGatewayMutation(gateway);
    }

    @Test
    void combinedAnalyzeReusesOneReconciliationWithoutRecapturingASnapshot() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        GatewayConnectionStatus connection = connected(false, "safe");
        ReconciliationReport reconciliationReport = report(false, cleanResource(),
                new ReconciliationSummary(1, 0, 1, 0, 0, 0, 0, 0));

        WriteReadinessReport result = service(false, gateway, reconciliation).analyze(connection, reconciliationReport);

        assertThat(result.readyForFutureExecution()).isTrue();
        assertThat(result.summary())
                .extracting(s -> s.managedPorts(), s -> s.validManagedPorts())
                .containsExactly(1, 1);
        assertThat(result.summary().managed()).isEqualTo(1);
        verifyNoInteractions(gateway);
        verify(reconciliation, never()).analyze();
        verify(reconciliation, never()).analyze(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enabledWriteFlagIsOnlyReportedAndStillCannotEnableRouterOsExecution() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        when(gateway.connectionStatus()).thenReturn(connected(false, "safe"));
        when(reconciliation.analyze()).thenReturn(report(false, cleanResource(),
                new ReconciliationSummary(1, 0, 1, 0, 0, 0, 0, 0)));

        WriteReadinessReport result = service(true, gateway, reconciliation).analyze();

        assertThat(result.writeFlagEnabled()).isTrue();
        assertThat(result.executionEnabled()).isFalse();
        assertThat(result.readyForFutureExecution()).isFalse();
        assertThat(check(result, "WRITE_FLAG_DISABLED"))
                .extracting(ReadinessCheck::satisfied, ReadinessCheck::severity)
                .containsExactly(false, ReadinessSeverity.WARNING);
        assertNoGatewayMutation(gateway);
    }

    @Test
    void foreignConflictIsBlockingWhileStillNeverEnablingExecution() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        when(gateway.connectionStatus()).thenReturn(connected(false, "safe"));
        when(reconciliation.analyze()).thenReturn(report(false, conflictResource(),
                new ReconciliationSummary(0, 1, 0, 0, 0, 1, 0, 0)));

        WriteReadinessReport result = service(false, gateway, reconciliation).analyze();

        assertThat(result.readyForFutureExecution()).isFalse();
        assertThat(check(result, "NO_FOREIGN_CONFLICT"))
                .extracting(ReadinessCheck::satisfied, ReadinessCheck::severity)
                .containsExactly(false, ReadinessSeverity.BLOCKING);
        assertThat(result.executionEnabled()).isFalse();
        assertNoGatewayMutation(gateway);
    }

    @Test
    void disconnectedGatewayReturnsStructuredBlockingReportWithoutTryingToReconcileOrLeakingSecrets() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        String secret = "s3cr3t-do-not-leak";
        when(gateway.connectionStatus()).thenReturn(new GatewayConnectionStatus(false, false, "router", 443,
                "version " + secret, 1L, "Authorization: Basic " + secret, true, false));

        WriteReadinessReport result = service(false, gateway, reconciliation, secret).analyze();

        assertThat(result.readyForFutureExecution()).isFalse();
        assertThat(result.executionEnabled()).isFalse();
        assertThat(check(result, "ROUTEROS_CONNECTED"))
                .extracting(ReadinessCheck::satisfied, ReadinessCheck::severity)
                .containsExactly(false, ReadinessSeverity.BLOCKING);
        assertThat(result.toString()).doesNotContain(secret, "Authorization");
        assertThat(result.checks()).allSatisfy(check -> assertThat(check.detail()).doesNotContain(secret, "Authorization"));
        verifyNoInteractions(reconciliation);
        assertNoGatewayMutation(gateway);
    }

    @Test
    void mockModeAndUnavailableSnapshotRemainObservationalAndNotFutureReady() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        when(gateway.connectionStatus()).thenReturn(connected(true, "safe"));
        when(reconciliation.analyze()).thenThrow(new IllegalStateException("password=should-not-leak"));

        WriteReadinessReport result = service(false, gateway, reconciliation).analyze();

        assertThat(result.mockMode()).isTrue();
        assertThat(result.readyForFutureExecution()).isFalse();
        assertThat(result.executionEnabled()).isFalse();
        assertThat(check(result, "INTERFACES_READABLE").severity()).isEqualTo(ReadinessSeverity.BLOCKING);
        assertThat(result.toString()).doesNotContain("password=should-not-leak");
        assertNoGatewayMutation(gateway);
    }

    private WriteReadinessService service(boolean writeEnabled, MikrotikGateway gateway,
                                           ReconciliationService reconciliation) {
        return service(writeEnabled, gateway, reconciliation, "very-secret-password");
    }

    private WriteReadinessService service(boolean writeEnabled, MikrotikGateway gateway,
                                          ReconciliationService reconciliation, String password) {
        return service(writeEnabled, gateway, reconciliation, password, List.of(clientEnabledPort()));
    }

    private WriteReadinessService service(boolean writeEnabled, MikrotikGateway gateway,
                                          ReconciliationService reconciliation, String password,
                                          List<com.mikrotikmanager.domain.ManagedPort> ports) {
        com.mikrotikmanager.persistence.ManagedPortRepository repository = mock(
                com.mikrotikmanager.persistence.ManagedPortRepository.class);
        when(repository.findAll()).thenReturn(ports);
        return new WriteReadinessService(new MikrotikProperties("router", 443, "panel", password, true,
                false, writeEnabled), gateway, reconciliation, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private com.mikrotikmanager.domain.ManagedPort clientEnabledPort() {
        return new com.mikrotikmanager.domain.ManagedPort(1L, "ether2",
                "Clientes", "metadata local", "10.10.10.0/24", "dhcp-clientes",
                com.mikrotikmanager.domain.ManagedPortRole.CLIENT, true, NOW, NOW);
    }

    private com.mikrotikmanager.domain.ManagedPort wanPort() {
        return new com.mikrotikmanager.domain.ManagedPort(2L, "ether1",
                "Internet", "uplink", null, null,
                com.mikrotikmanager.domain.ManagedPortRole.WAN, true, NOW, NOW);
    }

    private com.mikrotikmanager.domain.ManagedPort clientDisabledPort() {
        return new com.mikrotikmanager.domain.ManagedPort(3L, "ether3",
                "Cliente desabilitado", "metadata local", "10.10.30.0/24", "dhcp-clientes",
                com.mikrotikmanager.domain.ManagedPortRole.CLIENT, false, NOW, NOW);
    }

    private com.mikrotikmanager.domain.ManagedPort clientEnabledPortWithoutCidr() {
        return new com.mikrotikmanager.domain.ManagedPort(4L, "ether4",
                "Cliente sem CIDR", "metadata local", null, "dhcp-clientes",
                com.mikrotikmanager.domain.ManagedPortRole.CLIENT, true, NOW, NOW);
    }

    private com.mikrotikmanager.domain.ManagedPort clientEnabledPortWithInvalidCidr() {
        return new com.mikrotikmanager.domain.ManagedPort(5L, "ether5",
                "Cliente CIDR inválido", "metadata local", "10.10.10/24", "dhcp-clientes",
                com.mikrotikmanager.domain.ManagedPortRole.CLIENT, true, NOW, NOW);
    }

    private GatewayConnectionStatus connected(boolean mockMode, String message) {
        return new GatewayConnectionStatus(true, mockMode, "router", 443, "7.20", 1L, message, true, false);
    }

    private ReconciliationReport report(boolean fastTrack, ReconciliationResource resource,
                                        ReconciliationSummary summary) {
        return new ReconciliationReport(NOW, "a".repeat(64), 3, 1, 2, 1, fastTrack, List.of(resource), summary);
    }

    private ReconciliationResource cleanResource() {
        return new ReconciliationResource("SIMPLE_QUEUE", "ether2", "Queue da porta ether2",
                ResourceOwnership.MANAGED, ReconciliationStatus.IN_SYNC, "mtmgr-port-ether2", "10.10.10.0/24",
                "mtmgr-port-ether2", "10.10.10.0/24", false, List.of());
    }

    private ReconciliationResource conflictResource() {
        return new ReconciliationResource("SIMPLE_QUEUE", "ether2", "Queue da porta ether2",
                ResourceOwnership.FOREIGN, ReconciliationStatus.CONFLICT, "mtmgr-port-ether2", "10.10.10.0/24",
                "manual", "10.10.10.0/24", true, List.of(new ReconciliationFinding("FOREIGN_QUEUE_CONFLICT",
                "Manual resource conflicts", PlanSeverity.BLOCKING)));
    }

    private ReconciliationResource notApplicable(String interfaceName) {
        return new ReconciliationResource("SIMPLE_QUEUE", interfaceName, "Queue da porta " + interfaceName,
                ResourceOwnership.UNKNOWN, ReconciliationStatus.NOT_APPLICABLE, "mtmgr-port-" + interfaceName, null,
                null, null, false, List.of(new ReconciliationFinding("PORT_QUEUE_NOT_APPLICABLE",
                "A porta não é uma porta CLIENT habilitada com CIDR local configurado.", PlanSeverity.INFO)));
    }

    private ReadinessCheck check(WriteReadinessReport report, String code) {
        return report.checks().stream().filter(check -> code.equals(check.code())).findFirst().orElseThrow();
    }

    private void assertNoGatewayMutation(MikrotikGateway gateway) {
        verify(gateway, never()).setPortSpeed(anyString(), any(SpeedLimit.class));
        verify(gateway, never()).setDeviceSpeed(anyString(), any(SpeedLimit.class));
        verify(gateway, never()).blockDevice(anyString());
        verify(gateway, never()).unblockDevice(anyString());
    }
}
