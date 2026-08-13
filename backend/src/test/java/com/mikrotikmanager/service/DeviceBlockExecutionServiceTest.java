package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.OperationPlan;
import com.mikrotikmanager.domain.PlanConflict;
import com.mikrotikmanager.domain.PlanTarget;
import com.mikrotikmanager.domain.ResourceOwnership;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterDhcpLease;
import com.mikrotikmanager.domain.RouterDhcpServer;
import com.mikrotikmanager.domain.RouterFirewallFilter;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.TrafficRate;
import com.mikrotikmanager.gateway.DeviceBlockMutationGateway;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteClientException;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteErrorType;
import com.mikrotikmanager.support.ApiErrorCode;
import com.mikrotikmanager.support.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceBlockExecutionServiceTest {
    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final String COMMENT = ManagedResourceIdentifier.expectedDeviceComment(MAC);
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");

    @Test
    void blockCreatesOnlyTheControlledRuleAndVerifiesSafeOrder() {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture fixture = fixture(mutation);
        RouterSnapshot before = snapshot(List.of(anchor()));
        RouterSnapshot after = snapshot(List.of(desiredRule(), anchor()));
        when(fixture.snapshots.capture()).thenReturn(before, after);

        DeviceView result = fixture.service.block(MAC);

        assertThat(result.routerDevice().macAddress()).isEqualTo(MAC);
        verify(mutation).createManagedDeviceBlockRule(any(), eq("*anchor"));
        verify(mutation, never()).deleteManagedDeviceBlockRule(anyString());
        verify(fixture.snapshots, org.mockito.Mockito.times(2)).capture();
        verify(fixture.audit).record("BLOCK_DEVICE", "DEVICE", MAC, "UNBLOCKED", "BLOCKED", true, null);
    }

    @Test
    void blockIsIdempotentAndDoesNotWriteWhenTheDesiredRuleAlreadyExists() {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture fixture = fixture(mutation);
        when(fixture.snapshots.capture()).thenReturn(snapshot(List.of(desiredRule(), anchor())));

        fixture.service.block(MAC);

        verify(mutation, never()).createManagedDeviceBlockRule(any(), any());
        verify(mutation, never()).deleteManagedDeviceBlockRule(anyString());
        verify(fixture.audit).record("BLOCK_DEVICE", "DEVICE", MAC, "BLOCKED", "NO_CHANGE", true, null);
    }

    @Test
    void unblockResolvesTheCurrentRouterOsIdAndVerifiesAbsence() {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture fixture = fixture(mutation);
        when(fixture.snapshots.capture()).thenReturn(snapshot(List.of(desiredRule(), anchor())), snapshot(List.of(anchor())));

        fixture.service.unblock(MAC);

        verify(mutation).deleteManagedDeviceBlockRule("*managed");
        verify(mutation, never()).createManagedDeviceBlockRule(any(), any());
        verify(fixture.audit).record("UNBLOCK_DEVICE", "DEVICE", MAC, "BLOCKED", "UNBLOCKED", true, null);
    }

    @Test
    void unblockIsIdempotentWhenThereIsNoManagedOrForeignBlock() {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture fixture = fixture(mutation);
        when(fixture.snapshots.capture()).thenReturn(snapshot(List.of(anchor())));

        fixture.service.unblock(MAC);

        verify(mutation, never()).deleteManagedDeviceBlockRule(anyString());
        verify(mutation, never()).createManagedDeviceBlockRule(any(), any());
        verify(fixture.audit).record("UNBLOCK_DEVICE", "DEVICE", MAC, "UNBLOCKED", "NO_CHANGE", true, null);
    }

    @Test
    void foreignManualRuleBlocksBothDirectionsWithoutWriting() {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture fixture = fixture(mutation);
        when(fixture.snapshots.capture()).thenReturn(snapshot(List.of(foreignRule(), anchor())));
        OperationPlan foreignPlan = plan(false, conflict("UNOWNED_BLOCK_RESOURCE"));
        when(fixture.planning.planFromSnapshot(any(), any())).thenReturn(foreignPlan);

        assertThatThrownBy(() -> fixture.service.unblock(MAC))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.UNOWNED_BLOCK_RESOURCE);

        verify(mutation, never()).deleteManagedDeviceBlockRule(anyString());
        verify(mutation, never()).createManagedDeviceBlockRule(any(), any());
    }

    @Test
    void duplicateManagedRulesAreAmbiguousAndDriftIsNotRepaired() {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture duplicate = fixture(mutation);
        when(duplicate.snapshots.capture()).thenReturn(snapshot(List.of(desiredRule(), desiredRule("*managed-2"), anchor())));
        OperationPlan duplicatePlan = plan(false, conflict("AMBIGUOUS_OWNERSHIP"));
        when(duplicate.planning.planFromSnapshot(any(), any())).thenReturn(duplicatePlan);

        assertThatThrownBy(() -> duplicate.service.block(MAC))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.AMBIGUOUS_OWNERSHIP);
        verify(mutation, never()).createManagedDeviceBlockRule(any(), any());

        Fixture drift = fixture(mutation);
        when(drift.snapshots.capture()).thenReturn(snapshot(List.of(driftedRule(), anchor())));
        OperationPlan driftPlan = plan(false, conflict("MANAGED_BLOCK_RULE_DRIFT"));
        when(drift.planning.planFromSnapshot(any(), any())).thenReturn(driftPlan);

        assertThatThrownBy(() -> drift.service.unblock(MAC))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ApiErrorCode.MANAGED_BLOCK_RULE_DRIFT);
        verify(mutation, never()).deleteManagedDeviceBlockRule(anyString());
    }

    @Test
    void timeoutAfterPutIsRecoveredByFreshReadWithoutRetryingPut() {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture fixture = fixture(mutation);
        when(fixture.snapshots.capture()).thenReturn(snapshot(List.of(anchor())), snapshot(List.of(desiredRule(), anchor())));
        doThrow(new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN))
                .when(mutation).createManagedDeviceBlockRule(any(), any());

        fixture.service.block(MAC);

        verify(mutation).createManagedDeviceBlockRule(any(), eq("*anchor"));
        verify(mutation, org.mockito.Mockito.times(1)).createManagedDeviceBlockRule(any(), any());
        verify(fixture.snapshots, org.mockito.Mockito.times(2)).capture();
    }

    @Test
    void timeoutAfterDeleteIsRecoveredWhenFreshReadShowsAbsence() {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture fixture = fixture(mutation);
        when(fixture.snapshots.capture()).thenReturn(snapshot(List.of(desiredRule(), anchor())), snapshot(List.of(anchor())));
        doThrow(new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN))
                .when(mutation).deleteManagedDeviceBlockRule("*managed");

        fixture.service.unblock(MAC);

        verify(mutation).deleteManagedDeviceBlockRule("*managed");
        verify(fixture.audit).record("UNBLOCK_DEVICE", "DEVICE", MAC, "BLOCKED", "UNBLOCKED", true, null);
    }

    @Test
    void lockSerializesTwoBlocksAndLeavesAtMostOneRule() throws Exception {
        DeviceBlockMutationGateway mutation = mock(DeviceBlockMutationGateway.class);
        Fixture fixture = fixture(mutation);
        AtomicBoolean created = new AtomicBoolean();
        AtomicInteger captures = new AtomicInteger();
        when(fixture.snapshots.capture()).thenAnswer(invocation -> {
            captures.incrementAndGet();
            return snapshot(created.get() ? List.of(desiredRule(), anchor()) : List.of(anchor()));
        });
        doAnswer(invocation -> {
            created.set(true);
            return null;
        }).when(mutation).createManagedDeviceBlockRule(any(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<DeviceView>> operations = List.of(() -> fixture.service.block(MAC), () -> fixture.service.block(MAC));
            List<Future<DeviceView>> results = executor.invokeAll(operations);
            for (Future<DeviceView> result : results) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }

        verify(mutation, org.mockito.Mockito.times(1)).createManagedDeviceBlockRule(any(), any());
        assertThat(captures.get()).isEqualTo(3);
    }

    private Fixture fixture(DeviceBlockMutationGateway mutation) {
        RouterSnapshotService snapshots = mock(RouterSnapshotService.class);
        OperationPlanningService planning = mock(OperationPlanningService.class);
        DeviceService devices = mock(DeviceService.class);
        OperationLockManager locks = new OperationLockManager();
        MikrotikWriteGuard guard = mock(MikrotikWriteGuard.class);
        AuditService audit = mock(AuditService.class);
        DeviceView view = new DeviceView(device(), null, null);
        when(devices.getDevice(MAC)).thenReturn(view);
        OperationPlan defaultPlan = plan(true);
        when(planning.planFromSnapshot(any(), any())).thenReturn(defaultPlan);
        return new Fixture(new DeviceBlockExecutionService(snapshots, planning, mutation, devices, locks, guard, audit),
                snapshots, planning, audit);
    }

    private OperationPlan plan(boolean ready, PlanConflict... conflicts) {
        OperationPlan plan = mock(OperationPlan.class);
        when(plan.target()).thenReturn(new PlanTarget(MAC, "Test device", MAC, "ether2"));
        when(plan.readyForFutureExecution()).thenReturn(ready);
        when(plan.conflicts()).thenReturn(List.of(conflicts));
        return plan;
    }

    private PlanConflict conflict(String code) {
        return new PlanConflict(code, "FIREWALL_MAC_RULE", "test", "10.10.10.21",
                ResourceOwnership.FOREIGN, "blocked", com.mikrotikmanager.domain.PlanSeverity.BLOCKING);
    }

    private RouterFirewallFilter anchor() {
        return new RouterFirewallFilter("*anchor", "accept", "forward", "manual", false, false);
    }

    private RouterFirewallFilter desiredRule() {
        return desiredRule("*managed");
    }

    private RouterFirewallFilter desiredRule(String id) {
        return new RouterFirewallFilter(id, "drop", "forward", COMMENT, false, false,
                null, null, MAC);
    }

    private RouterFirewallFilter foreignRule() {
        return new RouterFirewallFilter("*foreign", "drop", "forward", "manual block", false, false,
                null, null, MAC);
    }

    private RouterFirewallFilter driftedRule() {
        return new RouterFirewallFilter("*drift", "accept", "forward", COMMENT, false, false,
                null, null, MAC);
    }

    private RouterSnapshot snapshot(List<RouterFirewallFilter> filters) {
        RouterDhcpLease lease = new RouterDhcpLease("*lease", MAC, "10.10.10.21", "dhcp-clientes", "ether2",
                "bound", false, "lease", true, false);
        return new RouterSnapshot(NOW,
                List.of(new RouterInterface("ether2", "ether", true, false, TrafficRate.UNAVAILABLE)),
                List.of(new RouterDhcpServer("*server", "dhcp-clientes", "ether2", false)),
                List.of(lease), List.of(device()), List.of(), filters, List.of());
    }

    private RouterDevice device() {
        return new RouterDevice("*lease", MAC, "Test device", "10.10.10.21", "dhcp-clientes", "ether2",
                DeviceStatus.ONLINE, false, "lease", SpeedLimit.UNLIMITED, TrafficRate.UNAVAILABLE, NOW);
    }

    private record Fixture(DeviceBlockExecutionService service, RouterSnapshotService snapshots,
                           OperationPlanningService planning, AuditService audit) {
    }
}
