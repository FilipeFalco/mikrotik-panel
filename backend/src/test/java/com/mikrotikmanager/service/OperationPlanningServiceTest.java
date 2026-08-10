package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.domain.OperationPlan;
import com.mikrotikmanager.domain.PlanConflict;
import com.mikrotikmanager.domain.PlanPrecondition;
import com.mikrotikmanager.domain.PlanSeverity;
import com.mikrotikmanager.domain.PlanWarning;
import com.mikrotikmanager.domain.PlannedOperationType;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.ReconciliationResource;
import com.mikrotikmanager.domain.ReconciliationStatus;
import com.mikrotikmanager.domain.ReconciliationSummary;
import com.mikrotikmanager.domain.ResourceOwnership;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterDhcpLease;
import com.mikrotikmanager.domain.RouterDhcpServer;
import com.mikrotikmanager.domain.RouterFirewallFilter;
import com.mikrotikmanager.domain.RouterAddressListEntry;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.RouterSimpleQueue;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OperationPlanningServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final String INTERFACE = "ether2";
    private static final String NETWORK = "10.10.10.0/24";
    private static final SpeedLimit PORT_LIMIT = new SpeedLimit(100_000_000L, 20_000_000L);

    @Test
    void generatesABlockDryRunForABoundDeviceButNeverAnExecutablePlan() {
        RouterSnapshot snapshot = snapshot(PORT_LIMIT, false, false, null, SpeedLimit.UNLIMITED);
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planBlockDevice(MAC);

        assertThat(plan.operationType()).isEqualTo(PlannedOperationType.BLOCK_DEVICE);
        assertThat(plan.target().identifier()).isEqualTo(MAC);
        assertThat(plan.currentState().ipAddress()).isEqualTo("10.10.10.21");
        assertThat(plan.currentState().interfaceName()).isEqualTo(INTERFACE);
        assertThat(plan.changeRequired()).isTrue();
        assertThat(plan.executable()).isFalse();
        assertThat(plan.readyForFutureExecution()).isFalse();
        assertThat(precondition(plan, "DEVICE_EXISTS").satisfied()).isTrue();
        assertThat(precondition(plan, "LEASE_BOUND").satisfied()).isTrue();
        assertThat(precondition(plan, "BLOCKING_STRATEGY_DECIDED"))
                .extracting(PlanPrecondition::satisfied, PlanPrecondition::severity)
                .containsExactly(false, PlanSeverity.BLOCKING);
        assertThat(warning(plan, "BLOCKING_STRATEGY_UNDECIDED")).isNotNull();
        assertThat(plan.plannedChanges()).singleElement().extracting(change -> change.action()).isEqualTo("CANDIDATE");

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void returnsAStructuredBlockingPlanForInvalidOrUnknownMacInsteadOfThrowing() {
        RouterSnapshot snapshot = snapshot(PORT_LIMIT, false, false, null, SpeedLimit.UNLIMITED);
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planBlockDevice("not-a-mac");

        assertThat(plan.operationType()).isEqualTo(PlannedOperationType.BLOCK_DEVICE);
        assertThat(plan.target().identifier()).isEqualTo("invalid-mac");
        assertThat(plan.changeRequired()).isFalse();
        assertThat(plan.executable()).isFalse();
        assertThat(precondition(plan, "DEVICE_HAS_MAC")).extracting(PlanPrecondition::satisfied, PlanPrecondition::severity)
                .containsExactly(false, PlanSeverity.BLOCKING);
        assertThat(precondition(plan, "DEVICE_EXISTS").satisfied()).isFalse();
        assertThat(plan.plannedChanges()).singleElement().extracting(change -> change.action()).isEqualTo("NO_ACTION");

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void blocksDeviceSpeedPlanWhenRequestedLimitExceedsFiniteParentPortLimit() {
        RouterSnapshot snapshot = snapshot(PORT_LIMIT, false, false, null, SpeedLimit.UNLIMITED);
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planDeviceSpeed(MAC, new SpeedLimit(150_000_000L, 10_000_000L));

        assertThat(plan.operationType()).isEqualTo(PlannedOperationType.SET_DEVICE_SPEED);
        assertThat(plan.changeRequired()).isTrue();
        assertThat(plan.executable()).isFalse();
        assertThat(plan.readyForFutureExecution()).isFalse();
        assertThat(precondition(plan, "PARENT_PORT_LIMIT_KNOWN").satisfied()).isTrue();
        assertThat(precondition(plan, "LIMIT_WITHIN_PARENT"))
                .extracting(PlanPrecondition::satisfied, PlanPrecondition::severity)
                .containsExactly(false, PlanSeverity.BLOCKING);
        assertThat(plan.plannedChanges()).singleElement().extracting(change -> change.action()).isEqualTo("UPDATE_CANDIDATE");

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void includesFastTrackAsAWarningForBandwidthPlanningWithoutBlockingAOtherwiseCleanPlan() {
        RouterSnapshot snapshot = snapshot(PORT_LIMIT, false, true, null, SpeedLimit.UNLIMITED);
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planPortSpeed(INTERFACE, new SpeedLimit(90_000_000L, 10_000_000L));

        assertThat(plan.operationType()).isEqualTo(PlannedOperationType.SET_PORT_SPEED);
        assertThat(plan.readyForFutureExecution()).isTrue();
        assertThat(plan.executable()).isFalse();
        assertThat(precondition(plan, "FASTTRACK_REVIEWED"))
                .extracting(PlanPrecondition::satisfied, PlanPrecondition::severity)
                .containsExactly(false, PlanSeverity.WARNING);
        assertThat(warning(plan, "FASTTRACK_ACTIVE"))
                .extracting(PlanWarning::severity)
                .isEqualTo(PlanSeverity.WARNING);

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void recognizesNoOpPortSpeedIntentAndKeepsItAsDryRunOnly() {
        RouterSnapshot snapshot = snapshot(PORT_LIMIT, false, false, null, SpeedLimit.UNLIMITED);
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planPortSpeed(INTERFACE, PORT_LIMIT);

        assertThat(plan.changeRequired()).isFalse();
        assertThat(plan.readyForFutureExecution()).isTrue();
        assertThat(plan.isNoOpCandidate()).isTrue();
        assertThat(plan.executable()).isFalse();
        assertThat(plan.plannedChanges()).singleElement().extracting(change -> change.action()).isEqualTo("NO_CHANGE");

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void doesNotPlanUnblockOfAForeignExistingBlock() {
        RouterSnapshot snapshot = snapshot(PORT_LIMIT, true, false, "manual block", SpeedLimit.UNLIMITED);
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planUnblockDevice(MAC);

        assertThat(plan.operationType()).isEqualTo(PlannedOperationType.UNBLOCK_DEVICE);
        assertThat(plan.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(plan.changeRequired()).isTrue();
        assertThat(plan.readyForFutureExecution()).isFalse();
        assertThat(plan.executable()).isFalse();
        assertThat(precondition(plan, "RESOURCE_OWNERSHIP_CONFIRMED").satisfied()).isFalse();
        assertThat(conflict(plan, "UNOWNED_BLOCK_RESOURCE"))
                .extracting(PlanConflict::ownership, PlanConflict::severity)
                .containsExactly(ResourceOwnership.FOREIGN, PlanSeverity.BLOCKING);

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void forwardDropFirewallRuleIsAnObservationalBlockCandidate() {
        RouterSnapshot snapshot = snapshotWithFirewall(PORT_LIMIT,
                List.of(new RouterFirewallFilter("*40", "drop", "forward", "Bloqueio manual", false, false,
                        "10.10.10.21", null)),
                List.of());
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planUnblockDevice(MAC);

        assertThat(plan.currentState().blocked()).isTrue();
        assertThat(plan.changeRequired()).isTrue();
        assertThat(plan.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(plan.readyForFutureExecution()).isFalse();
        assertThat(conflict(plan, "UNOWNED_BLOCK_RESOURCE"))
                .extracting(PlanConflict::ownership, PlanConflict::severity)
                .containsExactly(ResourceOwnership.FOREIGN, PlanSeverity.BLOCKING);

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void forwardRejectFirewallRuleIsAlsoAnObservationalBlockCandidate() {
        RouterSnapshot snapshot = snapshotWithFirewall(PORT_LIMIT,
                List.of(new RouterFirewallFilter("*41", "reject", "forward", "Bloqueio manual reject", false, false,
                        "10.10.10.21/32", null)),
                List.of());
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planUnblockDevice(MAC);

        assertThat(plan.currentState().blocked()).isTrue();
        assertThat(plan.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(conflict(plan, "UNOWNED_BLOCK_RESOURCE")).isNotNull();

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void inputDropFirewallRuleIsNotTreatedAsAClientBlock() {
        RouterSnapshot snapshot = snapshotWithFirewall(PORT_LIMIT,
                List.of(new RouterFirewallFilter("*42", "drop", "input", "Protege o proprio MikroTik", false, false,
                        "10.10.10.21", null)),
                List.of());
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planUnblockDevice(MAC);

        assertThat(plan.currentState().blocked()).isFalse();
        assertThat(plan.changeRequired()).isFalse();
        assertThat(plan.readyForFutureExecution()).isTrue();
        assertThat(plan.conflicts().stream().filter(conflict -> "UNOWNED_BLOCK_RESOURCE".equals(conflict.code())))
                .isEmpty();
        assertThat(precondition(plan, "NO_OWNED_BLOCK_TO_REMOVE").satisfied()).isTrue();

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void inputDropWithAddressListIsNotTreatedAsAClientBlock() {
        RouterSnapshot snapshot = snapshotWithFirewall(PORT_LIMIT,
                List.of(new RouterFirewallFilter("*43", "drop", "input", "input list", false, false,
                        null, "blocked-devices")),
                List.of(new RouterAddressListEntry("*50", "blocked-devices", "10.10.10.21", "manual", false, false)));
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planUnblockDevice(MAC);

        assertThat(plan.currentState().blocked()).isFalse();
        assertThat(plan.changeRequired()).isFalse();
        assertThat(plan.conflicts().stream().filter(conflict -> "UNOWNED_BLOCK_RESOURCE".equals(conflict.code())))
                .isEmpty();

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void forwardDropWithAddressListIsAnObservationalBlockCandidate() {
        RouterSnapshot snapshot = snapshotWithFirewall(PORT_LIMIT,
                List.of(new RouterFirewallFilter("*44", "drop", "forward", "forward list", false, false,
                        null, "blocked-devices")),
                List.of(new RouterAddressListEntry("*50", "blocked-devices", "10.10.10.21/32", "manual", false, false)));
        PlanningHarness harness = harness(snapshot);

        OperationPlan plan = harness.service().planUnblockDevice(MAC);

        assertThat(plan.currentState().blocked()).isTrue();
        assertThat(plan.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(conflict(plan, "UNOWNED_BLOCK_RESOURCE"))
                .extracting(PlanConflict::ownership, PlanConflict::severity)
                .containsExactly(ResourceOwnership.FOREIGN, PlanSeverity.BLOCKING);

        harness.assertOnlyReadPlanningDependenciesUsed(snapshot);
    }

    @Test
    void givesNewSnapshotsDistinctFingerprintsAndRecomputesDryRunState() {
        RouterSnapshot firstSnapshot = snapshot(PORT_LIMIT, false, false, null, SpeedLimit.UNLIMITED);
        RouterSnapshot secondSnapshot = snapshot(new SpeedLimit(50_000_000L, 10_000_000L), false, false, null,
                SpeedLimit.UNLIMITED);
        RouterSnapshotService snapshots = mock(RouterSnapshotService.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        ManagedPortRepository ports = mock(ManagedPortRepository.class);
        AuditService audit = mock(AuditService.class);
        when(snapshots.capture()).thenReturn(firstSnapshot, secondSnapshot);
        when(reconciliation.analyze(firstSnapshot)).thenReturn(reconciliation(firstSnapshot));
        when(reconciliation.analyze(secondSnapshot)).thenReturn(reconciliation(secondSnapshot));
        when(ports.findAll()).thenReturn(List.of(clientPort()));
        OperationPlanningService service = new OperationPlanningService(snapshots, reconciliation, ports, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        OperationPlan first = service.planPortSpeed(INTERFACE, PORT_LIMIT);
        OperationPlan second = service.planPortSpeed(INTERFACE, PORT_LIMIT);

        assertThat(first.snapshotFingerprint()).isNotEqualTo(second.snapshotFingerprint());
        assertThat(first.changeRequired()).isFalse();
        assertThat(second.changeRequired()).isTrue();
        assertThat(first.executable()).isFalse();
        assertThat(second.executable()).isFalse();
        verify(snapshots, times(2)).capture();
        verify(reconciliation).analyze(firstSnapshot);
        verify(reconciliation).analyze(secondSnapshot);
        verify(ports, times(2)).findAll();
        verify(audit, times(2)).record(org.mockito.ArgumentMatchers.eq("OPERATION_PLAN_CREATED"),
                org.mockito.ArgumentMatchers.eq("OPERATION_PLAN"), org.mockito.ArgumentMatchers.eq(INTERFACE),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.isNull());
        verifyNoMoreInteractions(snapshots, reconciliation, ports, audit);
    }

    private PlanningHarness harness(RouterSnapshot snapshot) {
        RouterSnapshotService snapshots = mock(RouterSnapshotService.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        ManagedPortRepository ports = mock(ManagedPortRepository.class);
        AuditService audit = mock(AuditService.class);
        when(snapshots.capture()).thenReturn(snapshot);
        when(reconciliation.analyze(snapshot)).thenReturn(reconciliation(snapshot));
        when(ports.findAll()).thenReturn(List.of(clientPort()));
        return new PlanningHarness(new OperationPlanningService(snapshots, reconciliation, ports, audit,
                Clock.fixed(NOW, ZoneOffset.UTC)), snapshots, reconciliation, ports, audit);
    }

    private RouterSnapshot snapshot(SpeedLimit portLimit, boolean blocked, boolean fastTrack, String leaseComment,
                                    SpeedLimit deviceLimit) {
        RouterDhcpLease lease = new RouterDhcpLease("*A1", MAC, "10.10.10.21", "dhcp-clientes", INTERFACE,
                "bound", blocked, leaseComment, true, false);
        RouterDevice device = new RouterDevice("*A1", MAC, "Galaxy S25", "10.10.10.21", "dhcp-clientes", INTERFACE,
                blocked ? DeviceStatus.BLOCKED : DeviceStatus.ONLINE, blocked, leaseComment, deviceLimit, null, NOW);
        RouterSimpleQueue queue = new RouterSimpleQueue("*20", ManagedResourceIdentifier.expectedPortQueueName(INTERFACE),
                ManagedResourceIdentifier.expectedPortComment(INTERFACE), NETWORK, portLimit, false, false);
        List<RouterFirewallFilter> filters = fastTrack
                ? List.of(new RouterFirewallFilter("*30", "fasttrack-connection", "forward", "defconf", false, false))
                : List.of();
        return new RouterSnapshot(NOW,
                List.of(new RouterInterface(INTERFACE, "ether", true, false, null)),
                List.of(new RouterDhcpServer("*11", "dhcp-clientes", INTERFACE, false)),
                List.of(lease), List.of(device), List.of(queue), filters, List.of());
    }

    private RouterSnapshot snapshotWithFirewall(SpeedLimit portLimit,
                                                List<RouterFirewallFilter> filters,
                                                List<RouterAddressListEntry> addressListEntries) {
        RouterDhcpLease lease = new RouterDhcpLease("*A1", MAC, "10.10.10.21", "dhcp-clientes", INTERFACE,
                "bound", false, null, true, false);
        RouterDevice device = new RouterDevice("*A1", MAC, "Galaxy S25", "10.10.10.21", "dhcp-clientes", INTERFACE,
                DeviceStatus.ONLINE, false, null, portLimit, null, NOW);
        RouterSimpleQueue queue = new RouterSimpleQueue("*20", ManagedResourceIdentifier.expectedPortQueueName(INTERFACE),
                ManagedResourceIdentifier.expectedPortComment(INTERFACE), NETWORK, portLimit, false, false);
        return new RouterSnapshot(NOW,
                List.of(new RouterInterface(INTERFACE, "ether", true, false, null)),
                List.of(new RouterDhcpServer("*11", "dhcp-clientes", INTERFACE, false)),
                List.of(lease), List.of(device), List.of(queue), filters, addressListEntries);
    }

    private ReconciliationReport reconciliation(RouterSnapshot snapshot) {
        ReconciliationResource resource = new ReconciliationResource("SIMPLE_QUEUE", INTERFACE, "Queue da porta ether2",
                ResourceOwnership.MANAGED, ReconciliationStatus.IN_SYNC,
                ManagedResourceIdentifier.expectedPortQueueName(INTERFACE), NETWORK,
                ManagedResourceIdentifier.expectedPortQueueName(INTERFACE), NETWORK, false, List.of());
        return new ReconciliationReport(NOW, snapshot.fingerprint(), 1, 1, 1, 1, snapshot.fastTrackDetected(),
                List.of(resource), new ReconciliationSummary(1, 0, 1, 0, 0, 0, 0, 0));
    }

    private ManagedPort clientPort() {
        return new ManagedPort(1L, INTERFACE, "Clientes", "metadata local", NETWORK, "dhcp-clientes",
                ManagedPortRole.CLIENT, true, NOW, NOW);
    }

    private PlanPrecondition precondition(OperationPlan plan, String code) {
        return plan.preconditions().stream().filter(value -> code.equals(value.code())).findFirst().orElseThrow();
    }

    private PlanWarning warning(OperationPlan plan, String code) {
        return plan.warnings().stream().filter(value -> code.equals(value.code())).findFirst().orElseThrow();
    }

    private PlanConflict conflict(OperationPlan plan, String code) {
        return plan.conflicts().stream().filter(value -> code.equals(value.code())).findFirst().orElseThrow();
    }

    private record PlanningHarness(OperationPlanningService service, RouterSnapshotService snapshots,
                                   ReconciliationService reconciliation, ManagedPortRepository ports, AuditService audit) {
        private void assertOnlyReadPlanningDependenciesUsed(RouterSnapshot snapshot) {
            verify(snapshots).capture();
            verify(reconciliation).analyze(snapshot);
            verify(ports).findAll();
            verify(audit).record(org.mockito.ArgumentMatchers.eq("OPERATION_PLAN_CREATED"),
                    org.mockito.ArgumentMatchers.eq("OPERATION_PLAN"), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.isNull());
            verifyNoMoreInteractions(snapshots, reconciliation, ports, audit);
        }
    }
}
