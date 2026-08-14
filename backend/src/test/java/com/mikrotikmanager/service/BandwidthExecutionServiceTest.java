package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.DeviceStatus;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.domain.ManagedSimpleQueue;
import com.mikrotikmanager.domain.OperationPlan;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterDhcpLease;
import com.mikrotikmanager.domain.RouterDhcpServer;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.RouterSimpleQueue;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.gateway.BandwidthMutationGateway;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteClientException;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteErrorType;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import com.mikrotikmanager.support.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the real executor; mocks exist only at its external boundaries. */
class BandwidthExecutionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final String IFACE = "ether2";
    private static final String NETWORK = "10.10.10.0/24";
    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final String MAC_B = "AA:BB:CC:DD:EE:02";
    private static final String MAC_C = "AA:BB:CC:DD:EE:03";
    private static final String IP = "10.10.10.45";
    private static final SpeedLimit LIMIT = new SpeedLimit(100_000_000, 20_000_000);

    @Test
    void portCreateCallsOnlyTheAllowListedCreateAndVerifiesDesiredState() {
        RouterSnapshot before = snapshot(List.of());
        RouterSimpleQueue created = queue("*Q1", ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT));
        Harness harness = harness(before, snapshot(List.of(created)), executablePlan(true));

        harness.service().setPortSpeed(IFACE, LIMIT);

        verify(harness.mutations()).createManagedQueue(ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT), null);
        verify(harness.mutations(), never()).updateManagedQueue(any(), any());
        verify(harness.mutations(), never()).deleteManagedQueue(any());
    }

    @Test
    void portExactNoOpReturnsReadModelWithoutAnyRouterOsMutation() {
        RouterSimpleQueue exact = queue("*Q1", ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT));
        Harness harness = harness(snapshot(List.of(exact)), snapshot(List.of(exact)), executablePlan(false));

        harness.service().setPortSpeed(IFACE, LIMIT);

        verifyNoInteractions(harness.mutations());
    }

    @Test
    void outcomeUnknownAfterCreateIsConfirmedByFreshGetWithoutSecondPut() {
        RouterSimpleQueue created = queue("*Q1", ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT));
        Harness harness = harness(snapshot(List.of()), snapshot(List.of(created)), executablePlan(true));
        doThrow(new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN))
                .when(harness.mutations()).createManagedQueue(any(), any());

        harness.service().setPortSpeed(IFACE, LIMIT);

        verify(harness.mutations(), times(1)).createManagedQueue(ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT), null);
    }

    @Test
    void outcomeUnknownAfterPatchIsConfirmedByFreshGetWithoutSecondPatch() {
        SpeedLimit oldLimit = new SpeedLimit(50_000_000, 10_000_000);
        RouterSimpleQueue old = queue("*Q1", ManagedSimpleQueue.port(IFACE, NETWORK, oldLimit));
        RouterSimpleQueue updated = queue("*Q1", ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT));
        Harness harness = harness(snapshot(List.of(old)), snapshot(List.of(updated)), executablePlan(true));
        doThrow(new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN))
                .when(harness.mutations()).updateManagedQueue(eq("*Q1"), any());

        harness.service().setPortSpeed(IFACE, LIMIT);

        verify(harness.mutations(), times(1)).updateManagedQueue("*Q1", ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT));
    }

    @Test
    void outcomeUnknownAfterDeleteIsConfirmedByFreshGetWithoutSecondDelete() {
        RouterSimpleQueue exact = queue("*Q1", ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT));
        Harness harness = harness(snapshot(List.of(exact)), snapshot(List.of()), executablePlan(true));
        doThrow(new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN))
                .when(harness.mutations()).deleteManagedQueue("*Q1");

        harness.service().setPortSpeed(IFACE, SpeedLimit.UNLIMITED);

        verify(harness.mutations(), times(1)).deleteManagedQueue("*Q1");
    }

    @Test
    void ambiguousOutcomeUnknownCreateNeverRetriesThePut() {
        ManagedSimpleQueue desired = ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT);
        Harness harness = harness(snapshot(List.of()), snapshot(List.of(queue("*Q1", desired), queue("*Q2", desired))), executablePlan(true));
        doThrow(new RouterOsWriteClientException(RouterOsWriteErrorType.OUTCOME_UNKNOWN))
                .when(harness.mutations()).createManagedQueue(any(), any());

        assertThatThrownBy(() -> harness.service().setPortSpeed(IFACE, LIMIT))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code().name())
                .isEqualTo("QUEUE_OWNERSHIP_AMBIGUOUS");

        verify(harness.mutations(), times(1)).createManagedQueue(ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT), null);
    }

    @Test
    void deviceTargetDriftIsPatchedOnceWithTheCompleteDesiredState() {
        ManagedSimpleQueue old = ManagedSimpleQueue.device(MAC, "10.10.10.21", "none", LIMIT);
        ManagedSimpleQueue desired = ManagedSimpleQueue.device(MAC, IP, "none", LIMIT);
        RouterSnapshot before = snapshot(List.of(queue("*Q1", old)));
        Harness harness = harness(before, snapshot(List.of(queue("*Q1", desired))), executablePlan(true));

        harness.service().setDeviceSpeed(MAC, LIMIT);

        verify(harness.mutations()).updateManagedQueue("*Q1", desired);
        verify(harness.mutations(), never()).createManagedQueue(any(), any());
        verify(harness.mutations(), never()).deleteManagedQueue(any());
    }

    @Test
    void deviceExactNoOpNeverTurnsIntoPatch() {
        ManagedSimpleQueue desired = ManagedSimpleQueue.device(MAC, IP, "none", LIMIT);
        Harness harness = harness(snapshot(List.of(queue("*Q1", desired))), snapshot(List.of(queue("*Q1", desired))), executablePlan(false));

        harness.service().setDeviceSpeed(MAC, LIMIT);

        verifyNoInteractions(harness.mutations());
    }

    @Test
    void parentCreationRejectsBurstDriftBeforeTheFirstWrite() {
        ManagedSimpleQueue childDesired = ManagedSimpleQueue.device(MAC, IP, "none", LIMIT);
        RouterSimpleQueue burstDrift = new RouterSimpleQueue("*Q1", childDesired.name(), childDesired.comment(), childDesired.target(), childDesired.maxLimit(),
                false, false, false, "none", null, null, null, "30M/30M", null, null, null, null, null, null);
        Harness harness = harness(snapshot(List.of(burstDrift)), snapshot(List.of(burstDrift)), executablePlan(true));

        assertThatThrownBy(() -> harness.service().setPortSpeed(IFACE, LIMIT))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code().name())
                .isEqualTo("MANAGED_QUEUE_DRIFT");

        verifyNoInteractions(harness.mutations());
    }

    @Test
    void portParentDriftCannotBePatchedByAnExecutionPath() {
        ManagedSimpleQueue desired = ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT);
        RouterSimpleQueue drifted = new RouterSimpleQueue("*Q1", desired.name(), desired.comment(), desired.target(), desired.maxLimit(),
                false, false, false, "foreign-parent", null, null, null, null, null, null, null, null, null, null);
        Harness harness = harness(snapshot(List.of(drifted)), snapshot(List.of(drifted)), executablePlan(true));

        assertThatThrownBy(() -> harness.service().setPortSpeed(IFACE, LIMIT)).isInstanceOf(ApiException.class);

        verifyNoInteractions(harness.mutations());
    }

    @Test
    void hierarchyPartialApplyStopsAfterFailedChildRefreshesStateAndNeverRollsBack() {
        SpeedLimit childLimit = new SpeedLimit(40_000_000, 5_000_000);
        ManagedSimpleQueue childA = ManagedSimpleQueue.device(MAC, IP, "none", childLimit);
        ManagedSimpleQueue childB = ManagedSimpleQueue.device(MAC_B, "10.10.10.46", "none", childLimit);
        ManagedSimpleQueue childC = ManagedSimpleQueue.device(MAC_C, "10.10.10.47", "none", childLimit);
        RouterSnapshot before = hierarchySnapshot(List.of(
                queue("*QA", childA), queue("*QB", childB), queue("*QC", childC)));
        Harness harness = harness(before, before, executablePlan(true));
        doThrow(new RouterOsWriteClientException(RouterOsWriteErrorType.REJECTED))
                .when(harness.mutations()).updateManagedQueue(eq("*QB"), any());

        assertThatThrownBy(() -> harness.service().setPortSpeed(IFACE, LIMIT))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code().name())
                .isEqualTo("QUEUE_PARTIAL_APPLY");

        ManagedSimpleQueue parent = ManagedSimpleQueue.port(IFACE, NETWORK, LIMIT);
        verify(harness.mutations()).createManagedQueue(parent, "*QA");
        verify(harness.mutations()).updateManagedQueue("*QA", withParent(childA, parent.name()));
        verify(harness.mutations()).updateManagedQueue("*QB", withParent(childB, parent.name()));
        verify(harness.mutations(), never()).updateManagedQueue(eq("*QC"), any());
        verify(harness.mutations(), never()).deleteManagedQueue(any());
        verify(harness.snapshots(), times(2)).capture();
    }

    private Harness harness(RouterSnapshot before, RouterSnapshot after, OperationPlan plan) {
        RouterSnapshotService snapshots = mock(RouterSnapshotService.class);
        when(snapshots.capture()).thenReturn(before, after);
        ManagedPortRepository ports = mock(ManagedPortRepository.class);
        when(ports.findByInterfaceName(IFACE)).thenReturn(java.util.Optional.of(port()));
        BandwidthMutationGateway mutations = mock(BandwidthMutationGateway.class);
        MikrotikWriteGuard guard = mock(MikrotikWriteGuard.class);
        PortService portService = mock(PortService.class);
        when(portService.getPort(IFACE)).thenReturn(mock(PortView.class));
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.getDevice(MAC)).thenReturn(mock(DeviceView.class));
        AuditService audit = mock(AuditService.class);
        OperationPlanningService planning = mock(OperationPlanningService.class);
        when(planning.planFromSnapshot(any(), any())).thenReturn(plan);
        return new Harness(new BandwidthExecutionService(snapshots, ports, mutations, new OperationLockManager(), guard,
                portService, deviceService, audit, planning), mutations, snapshots);
    }

    private OperationPlan executablePlan(boolean changeRequired) {
        OperationPlan plan = mock(OperationPlan.class);
        when(plan.changeRequired()).thenReturn(changeRequired);
        when(plan.readyForFutureExecution()).thenReturn(true);
        when(plan.conflicts()).thenReturn(List.of());
        when(plan.preconditions()).thenReturn(List.of());
        return plan;
    }

    private RouterSnapshot snapshot(List<RouterSimpleQueue> queues) {
        RouterDevice device = new RouterDevice("*L1", MAC, "Phone", IP, "dhcp-clientes", IFACE, DeviceStatus.ONLINE,
                false, null, SpeedLimit.UNLIMITED, null, NOW);
        RouterDhcpLease lease = new RouterDhcpLease("*L1", MAC, IP, "dhcp-clientes", IFACE, "bound", false, null, true, false);
        return new RouterSnapshot(NOW, List.of(new RouterInterface(IFACE, "ether", true, false, null)),
                List.of(new RouterDhcpServer("*D1", "dhcp-clientes", IFACE, false)), List.of(lease), List.of(device), queues, List.of(), List.of());
    }

    private RouterSnapshot hierarchySnapshot(List<RouterSimpleQueue> queues) {
        List<RouterDevice> devices = List.of(
                device("*L1", MAC, IP), device("*L2", MAC_B, "10.10.10.46"), device("*L3", MAC_C, "10.10.10.47"));
        List<RouterDhcpLease> leases = List.of(
                lease("*L1", MAC, IP), lease("*L2", MAC_B, "10.10.10.46"), lease("*L3", MAC_C, "10.10.10.47"));
        return new RouterSnapshot(NOW, List.of(new RouterInterface(IFACE, "ether", true, false, null)),
                List.of(new RouterDhcpServer("*D1", "dhcp-clientes", IFACE, false)), leases, devices, queues, List.of(), List.of());
    }

    private RouterDevice device(String id, String mac, String ip) {
        return new RouterDevice(id, mac, "Phone", ip, "dhcp-clientes", IFACE, DeviceStatus.ONLINE,
                false, null, SpeedLimit.UNLIMITED, null, NOW);
    }

    private RouterDhcpLease lease(String id, String mac, String ip) {
        return new RouterDhcpLease(id, mac, ip, "dhcp-clientes", IFACE, "bound", false, null, true, false);
    }

    private RouterSimpleQueue queue(String id, ManagedSimpleQueue desired) {
        return new RouterSimpleQueue(id, desired.name(), desired.comment(), desired.target(), desired.maxLimit(), false, false);
    }

    private ManagedSimpleQueue withParent(ManagedSimpleQueue queue, String parent) {
        return new ManagedSimpleQueue(queue.name(), queue.comment(), queue.target(), parent, queue.maxLimit());
    }

    private ManagedPort port() {
        return new ManagedPort(1L, IFACE, "Clientes", "", NETWORK, "dhcp-clientes", ManagedPortRole.CLIENT, true, NOW, NOW);
    }

    private record Harness(BandwidthExecutionService service, BandwidthMutationGateway mutations, RouterSnapshotService snapshots) { }
}
