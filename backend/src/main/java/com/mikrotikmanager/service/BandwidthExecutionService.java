package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.BandwidthLimitPolicy;
import com.mikrotikmanager.domain.ManagedSimpleQueue;
import com.mikrotikmanager.domain.ManagedSimpleQueueSemantics;
import com.mikrotikmanager.domain.OperationPlan;
import com.mikrotikmanager.domain.PlanConflict;
import com.mikrotikmanager.domain.PlanPrecondition;
import com.mikrotikmanager.domain.PlanSeverity;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterSimpleQueue;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.SetDeviceSpeedIntent;
import com.mikrotikmanager.domain.SetPortSpeedIntent;
import com.mikrotikmanager.gateway.BandwidthMutationGateway;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteClientException;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteErrorType;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import com.mikrotikmanager.support.ApiErrorCode;
import com.mikrotikmanager.support.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fase 5 TOCTOU-safe Simple Queue executor. It accepts only domain intent,
 * serializes the shared ordered queue collection, re-reads after every write
 * outcome uncertainty, and never adopts non-exact resources.
 */
@Service
public final class BandwidthExecutionService {
    private final RouterSnapshotService snapshots;
    private final ManagedPortRepository ports;
    private final BandwidthMutationGateway mutations;
    private final OperationLockManager locks;
    private final MikrotikWriteGuard guard;
    private final PortService portService;
    private final DeviceService deviceService;
    private final AuditService audit;
    private final OperationPlanningService planning;

    public BandwidthExecutionService(RouterSnapshotService snapshots, ManagedPortRepository ports,
                                     BandwidthMutationGateway mutations, OperationLockManager locks,
                                     MikrotikWriteGuard guard, PortService portService, DeviceService deviceService,
                                     AuditService audit, OperationPlanningService planning) {
        this.snapshots = Objects.requireNonNull(snapshots); this.ports = Objects.requireNonNull(ports);
        this.mutations = Objects.requireNonNull(mutations); this.locks = Objects.requireNonNull(locks);
        this.guard = Objects.requireNonNull(guard); this.portService = Objects.requireNonNull(portService);
        this.deviceService = Objects.requireNonNull(deviceService); this.audit = Objects.requireNonNull(audit);
        this.planning = Objects.requireNonNull(planning);
    }

    public PortView setPortSpeed(String iface, SpeedLimit requested) {
        return locks.withLock("ROUTEROS:SIMPLE_QUEUE", () -> locks.withLock("PORT:" + iface, () -> {
            guard.checkBandwidthWriteAllowed(); validateLimit(requested);
            RouterSnapshot before = snapshots.capture();
            OperationPlan freshPlan = planning.planFromSnapshot(new SetPortSpeedIntent(iface, requested), before);
            // This is deliberately before every queue mutation path. The
            // executor may never turn a preview NO_CHANGE into a PATCH.
            if (!freshPlan.changeRequired()) return portService.getPort(iface);
            requireExecutablePreflight(freshPlan);
            if (before.fastTrackDetected()) throw error(ApiErrorCode.FASTTRACK_BYPASSES_SIMPLE_QUEUE, HttpStatus.CONFLICT,
                    "FastTrack ativo impede a aplicação segura deste limite por Simple Queue nesta fase.");
            ManagedPort local = ports.findByInterfaceName(iface).orElseThrow(() -> error(ApiErrorCode.PORT_NOT_FOUND, HttpStatus.NOT_FOUND, "Porta não encontrada."));
            requireManagedPort(local); String network = local.network();
            try {
                if (requested.isUnlimited()) removePort(before, iface);
                else setPort(before, iface, network, requested);
                RouterSnapshot after = snapshots.capture(); verifyPort(after, iface, network, requested);
                audit.record("PORT_SPEED_CHANGED", "PORT", iface, "sanitized", "download=" + requested.downloadBps() + ";upload=" + requested.uploadBps(), true, null);
                return portService.getPort(iface);
            } catch (RuntimeException ex) {
                audit.record("PORT_SPEED_CHANGED", "PORT", iface, "sanitized", "sanitized", false, code(ex)); throw ex;
            }
        }));
    }

    public DeviceView setDeviceSpeed(String rawMac, SpeedLimit requested) {
        final String mac;
        try { mac = ManagedResourceIdentifier.normalizeMac(rawMac); } catch (IllegalArgumentException e) { throw error(ApiErrorCode.INVALID_INPUT, HttpStatus.BAD_REQUEST, e.getMessage()); }
        return locks.withLock("ROUTEROS:SIMPLE_QUEUE", () -> locks.withLock("DEVICE:" + mac, () -> {
            guard.checkBandwidthWriteAllowed(); validateLimit(requested);
            RouterSnapshot before = snapshots.capture();
            OperationPlan freshPlan = planning.planFromSnapshot(new SetDeviceSpeedIntent(mac, requested), before);
            // The same structural guard applies to the DHCP target-drift
            // reconciliation path and to ordinary device no-ops.
            if (!freshPlan.changeRequired()) return deviceService.getDevice(mac);
            requireExecutablePreflight(freshPlan);
            if (before.fastTrackDetected()) throw error(ApiErrorCode.FASTTRACK_BYPASSES_SIMPLE_QUEUE, HttpStatus.CONFLICT,
                    "FastTrack ativo impede a aplicação segura deste limite por Simple Queue nesta fase.");
            RouterDevice device = uniqueDevice(before, mac);
            ManagedPort port = ports.findByInterfaceName(device.interfaceName()).orElseThrow(() -> error(ApiErrorCode.QUEUE_PARENT_INVALID, HttpStatus.CONFLICT, "A porta do dispositivo não possui metadata local segura."));
            requireManagedPort(port);
            try {
                if (requested.isUnlimited()) removeDevice(before, mac);
                else setDevice(before, device, port, requested);
                RouterSnapshot after = snapshots.capture(); verifyDevice(after, mac, device.ipAddress(), requested);
                audit.record("DEVICE_SPEED_CHANGED", "DEVICE", mac, "sanitized", "download=" + requested.downloadBps() + ";upload=" + requested.uploadBps(), true, null);
                return deviceService.getDevice(mac);
            } catch (RuntimeException ex) {
                audit.record("DEVICE_SPEED_CHANGED", "DEVICE", mac, "sanitized", "sanitized", false, code(ex)); throw ex;
            }
        }));
    }

    private void setPort(RouterSnapshot snapshot, String iface, String network, SpeedLimit limit) {
        ManagedSimpleQueue desired = ManagedSimpleQueue.port(iface, network, limit);
        List<RouterSimpleQueue> owned = ownedPort(snapshot, iface); requireUnique(owned);
        conflictForPort(snapshot, network, desired, iface);
        List<RouterSimpleQueue> children = preflightPortHierarchy(snapshot, iface, desired, limit, owned.isEmpty());
        if (owned.isEmpty()) {
            String anchor = children.stream().map(RouterSimpleQueue::id).filter(this::validId).findFirst().orElse(null);
            writeCreate(desired, anchor);
            // Every candidate passed preflight before the parent was created.
            for (RouterSimpleQueue child : children) if (!desired.name().equals(parent(child))) writeUpdate(child, withParent(child, desired.name()));
        } else {
            RouterSimpleQueue current = owned.getFirst(); requireExactPort(current, iface, network);
            if (!same(current, desired)) writeUpdate(current, desired);
            for (RouterSimpleQueue child : children) if (!desired.name().equals(parent(child))) writeUpdate(child, withParent(child, desired.name()));
        }
    }

    private void removePort(RouterSnapshot snapshot, String iface) {
        List<RouterSimpleQueue> owned = ownedPort(snapshot, iface); requireUnique(owned); if (owned.isEmpty()) return;
        RouterSimpleQueue parent = owned.getFirst();
        ManagedPort local = ports.findByInterfaceName(iface).orElseThrow(() -> error(ApiErrorCode.PORT_NOT_FOUND, HttpStatus.NOT_FOUND, "Porta não encontrada."));
        requireExactPort(parent, iface, local.network());
        String name = parent.name();
        List<RouterSimpleQueue> allChildren = snapshot.simpleQueues().stream().filter(q -> name.equals(parent(q))).toList();
        // Removing a hierarchy also validates all dependents before the first
        // PATCH, avoiding a partially detached tree caused by late drift.
        for (RouterSimpleQueue child : allChildren) {
            if (!isManagedDevice(snapshot, child)) throw error(ApiErrorCode.QUEUE_FOREIGN_CONFLICT, HttpStatus.CONFLICT, "Uma fila não gerenciada depende da fila da porta.");
            RouterDevice childDevice = deviceForQueue(snapshot, child);
            requireExactDevice(child, childDevice, name, child.maxLimit());
        }
        for (RouterSimpleQueue child : allChildren) writeUpdate(child, withParent(child, "none"));
        writeDelete(parent);
    }

    private void setDevice(RouterSnapshot snapshot, RouterDevice device, ManagedPort port, SpeedLimit limit) {
        RouterSimpleQueue parent = singlePort(snapshot, port.interfaceName());
        String parentName = "none";
        if (parent != null) { requireExactPort(parent, port.interfaceName(), port.network()); if (exceeds(parent.maxLimit(), limit)) throw error(ApiErrorCode.QUEUE_PARENT_LIMIT_EXCEEDED, HttpStatus.CONFLICT, "O limite do dispositivo não pode ultrapassar o limite finito da porta."); parentName = parent.name(); }
        ManagedSimpleQueue desired = ManagedSimpleQueue.device(device.macAddress(), device.ipAddress(), parentName, limit);
        List<RouterSimpleQueue> owned = ownedDevice(snapshot, device.macAddress()); requireUnique(owned);
        conflictForDevice(snapshot, desired, device.macAddress());
        if (owned.isEmpty()) writeCreate(desired, null);
        else { RouterSimpleQueue current = owned.getFirst(); requireExactDeviceExceptTarget(current, device, parentName); if (!same(current, desired)) writeUpdate(current, desired); }
    }

    private void removeDevice(RouterSnapshot snapshot, String mac) {
        List<RouterSimpleQueue> owned = ownedDevice(snapshot, mac); requireUnique(owned); if (owned.isEmpty()) return;
        RouterSimpleQueue queue = owned.getFirst(); RouterDevice device = uniqueDevice(snapshot, mac);
        String parentName = expectedExistingParent(snapshot, device);
        requireExactDevice(queue, device, parentName, queue.maxLimit()); writeDelete(queue);
    }

    private void verifyPort(RouterSnapshot snapshot, String iface, String network, SpeedLimit requested) {
        List<RouterSimpleQueue> queues = ownedPort(snapshot, iface); if (requested.isUnlimited()) { if (!queues.isEmpty()) failVerify(); return; }
        requireUnique(queues); if (queues.isEmpty() || !same(queues.getFirst(), ManagedSimpleQueue.port(iface, network, requested))) failVerify();
        String parent = queues.getFirst().name(); for (RouterSimpleQueue child : managedDeviceChildrenForPort(snapshot, iface)) if (!parent.equals(parent(child))) failVerify();
    }
    private void verifyDevice(RouterSnapshot snapshot, String mac, String ip, SpeedLimit requested) {
        List<RouterSimpleQueue> queues = ownedDevice(snapshot, mac); if (requested.isUnlimited()) { if (!queues.isEmpty()) failVerify(); return; }
        requireUnique(queues);
        RouterDevice device = uniqueDevice(snapshot, mac);
        ManagedSimpleQueue desired = ManagedSimpleQueue.device(mac, ip, expectedExistingParent(snapshot, device), requested);
        if (queues.isEmpty() || !same(queues.getFirst(), desired)) failVerify();
    }

    /**
     * Full preflight for an explicit PORT hierarchy operation. No caller may
     * create the parent until every managed child is unique, exact apart from
     * the allowed parent transition, safe, bound and below the new parent.
     */
    private List<RouterSimpleQueue> preflightPortHierarchy(RouterSnapshot snapshot, String iface,
                                                           ManagedSimpleQueue parentDesired, SpeedLimit parentLimit,
                                                           boolean creatingParent) {
        List<RouterSimpleQueue> children = new ArrayList<>();
        for (RouterDevice device : snapshot.devices()) {
            if (!iface.equals(device.interfaceName())) continue;
            List<RouterSimpleQueue> owned = ownedDevice(snapshot, device.macAddress());
            requireUnique(owned);
            if (owned.isEmpty()) continue;
            RouterSimpleQueue child = owned.getFirst();
            String currentParent = parent(child);
            boolean parentAcceptable = "none".equals(currentParent)
                    || (!creatingParent && parentDesired.name().equals(currentParent));
            if (!parentAcceptable) throw error(ApiErrorCode.MANAGED_QUEUE_DRIFT, HttpStatus.CONFLICT,
                    "O parent atual de um filho DEVICE_QUEUE não é aceitável para esta operação explícita de hierarquia.");
            requireBoundDevice(snapshot, device);
            requireExactDevice(child, device, currentParent, child.maxLimit());
            if (exceeds(parentLimit, child.maxLimit())) throw error(ApiErrorCode.QUEUE_PARENT_LIMIT_EXCEEDED,
                    HttpStatus.CONFLICT, "Um limite individual de dispositivo ultrapassa o novo limite da porta.");
            children.add(child);
        }
        boolean foreignDependent = snapshot.simpleQueues().stream()
                .filter(queue -> parentDesired.name().equals(parent(queue)))
                .anyMatch(queue -> !children.contains(queue));
        if (foreignDependent) throw error(ApiErrorCode.QUEUE_FOREIGN_CONFLICT, HttpStatus.CONFLICT,
                "Uma fila foreign ou não comprovadamente gerenciada depende da fila da porta.");
        return List.copyOf(children);
    }

    private RouterDevice deviceForQueue(RouterSnapshot snapshot, RouterSimpleQueue queue) {
        return snapshot.devices().stream()
                .filter(device -> ManagedSimpleQueueSemantics.isOwnedDevice(queue, device.macAddress()))
                .findFirst()
                .orElseThrow(() -> error(ApiErrorCode.MANAGED_QUEUE_DRIFT, HttpStatus.CONFLICT,
                        "A fila DEVICE_QUEUE não possui dispositivo DHCP atual para validação."));
    }

    private void requireBoundDevice(RouterSnapshot snapshot, RouterDevice device) {
        List<com.mikrotikmanager.domain.RouterDhcpLease> leases = snapshot.dhcpLeases().stream()
                .filter(lease -> device.macAddress().equals(lease.macAddress()))
                .toList();
        if (leases.size() != 1 || !leases.getFirst().isBound() || !Objects.equals(device.ipAddress(), leases.getFirst().ipAddress())) {
            throw error(ApiErrorCode.MANAGED_QUEUE_DRIFT, HttpStatus.CONFLICT,
                    "A fila DEVICE_QUEUE exige uma lease DHCP única, bound e coerente antes da alteração.");
        }
    }

    private void requireExactPort(RouterSimpleQueue queue, String iface, String network) {
        if (!ManagedSimpleQueueSemantics.isOwnedPort(queue, iface)
                || !same(queue, ManagedSimpleQueue.port(iface, network, queue.maxLimit()))) {
            throw error(ApiErrorCode.MANAGED_QUEUE_DRIFT, HttpStatus.CONFLICT,
                    "A Simple Queue MTMGR da porta divergiu do desired-state seguro e não será reparada automaticamente.");
        }
    }

    /** Validates exact device shape while allowing a caller-selected target update only. */
    private void requireExactDeviceExceptTarget(RouterSimpleQueue queue, RouterDevice device, String expectedParent) {
        if (!ManagedSimpleQueueSemantics.isOwnedDevice(queue, device.macAddress())
                || !ManagedSimpleQueueSemantics.isSafeManagedQueue(queue)
                || !ManagedResourceIdentifier.expectedDeviceQueueName(device.macAddress()).equals(queue.name())
                || !ManagedResourceIdentifier.expectedDeviceComment(device.macAddress()).equals(queue.comment())
                || !expectedParent.equals(parent(queue))) {
            throw error(ApiErrorCode.MANAGED_QUEUE_DRIFT, HttpStatus.CONFLICT,
                    "Somente TARGET_DRIFT de DEVICE_QUEUE pode ser convergido; outro drift exige correção manual.");
        }
        requireBoundDeviceForTargetRepair(device);
    }

    private void requireBoundDeviceForTargetRepair(RouterDevice device) {
        if (device.ipAddress() == null || device.ipAddress().isBlank()) {
            throw error(ApiErrorCode.MANAGED_QUEUE_DRIFT, HttpStatus.CONFLICT,
                    "A fila DEVICE_QUEUE não possui target DHCP atual válido.");
        }
    }

    private void requireExactDevice(RouterSimpleQueue queue, RouterDevice device, String expectedParent, SpeedLimit limit) {
        if (device == null || device.ipAddress() == null || !same(queue,
                ManagedSimpleQueue.device(device.macAddress(), device.ipAddress(), expectedParent, limit))) {
            throw error(ApiErrorCode.MANAGED_QUEUE_DRIFT, HttpStatus.CONFLICT,
                    "A Simple Queue MTMGR do dispositivo divergiu do desired-state seguro e não será reparada automaticamente.");
        }
    }

    private String expectedExistingParent(RouterSnapshot snapshot, RouterDevice device) {
        List<RouterSimpleQueue> parents = ownedPort(snapshot, device.interfaceName());
        requireUnique(parents);
        if (parents.isEmpty()) return "none";
        RouterSimpleQueue parent = parents.getFirst();
        ManagedPort local = ports.findByInterfaceName(device.interfaceName()).orElse(null);
        if (local == null || !ManagedSimpleQueueSemantics.matchesDesired(parent,
                ManagedSimpleQueue.port(device.interfaceName(), local.network(), parent.maxLimit()))) return "none";
        return parent.name();
    }

    private void writeCreate(ManagedSimpleQueue desired, String anchor) { try { mutations.createManagedQueue(desired, anchor); } catch (RouterOsWriteClientException e) { recoverOrThrow(e, desired, null); } }
    private void writeUpdate(RouterSimpleQueue old, ManagedSimpleQueue desired) { if (!validId(old.id())) failVerify(); try { mutations.updateManagedQueue(old.id(), desired); } catch (RouterOsWriteClientException e) { recoverOrThrow(e, desired, old.id()); } }
    private void writeDelete(RouterSimpleQueue old) { if (!validId(old.id())) failVerify(); try { mutations.deleteManagedQueue(old.id()); } catch (RouterOsWriteClientException e) { if (e.errorType() == RouterOsWriteErrorType.OUTCOME_UNKNOWN || e.errorType() == RouterOsWriteErrorType.NOT_FOUND) { if (snapshots.capture().simpleQueues().stream().noneMatch(q -> old.id().equals(q.id()))) return; } throw writeFailure(e); } }
    private void recoverOrThrow(RouterOsWriteClientException e, ManagedSimpleQueue desired, String id) { if (e.errorType() == RouterOsWriteErrorType.OUTCOME_UNKNOWN) { List<RouterSimpleQueue> matches = snapshots.capture().simpleQueues().stream().filter(q -> same(q, desired)).toList(); if (matches.size() == 1) return; if (matches.size() > 1) throw error(ApiErrorCode.QUEUE_OWNERSHIP_AMBIGUOUS, HttpStatus.CONFLICT, "O resultado da escrita é ambíguo."); } throw writeFailure(e); }
    private ApiException writeFailure(RouterOsWriteClientException e) {
        // RouterOS has no transaction for the parent/child sequence. Refresh
        // the observed state before reporting a failed mutation; never issue a
        // blind rollback or advance to a later child.
        snapshots.capture();
        return error(e.errorType() == RouterOsWriteErrorType.OUTCOME_UNKNOWN ? ApiErrorCode.QUEUE_WRITE_OUTCOME_UNKNOWN : ApiErrorCode.QUEUE_PARTIAL_APPLY,
                e.errorType() == RouterOsWriteErrorType.OUTCOME_UNKNOWN ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                "A alteração de Simple Queue não pôde ser confirmada; nenhuma repetição cega foi feita.");
    }

    private List<RouterSimpleQueue> ownedPort(RouterSnapshot s, String iface) { return s.simpleQueues().stream().filter(q -> ManagedResourceIdentifier.isOwnedByPort(q.comment(), iface)).toList(); }
    private List<RouterSimpleQueue> ownedDevice(RouterSnapshot s, String mac) { return s.simpleQueues().stream().filter(q -> ManagedResourceIdentifier.isOwnedByDevice(q.comment(), mac)).toList(); }
    private RouterSimpleQueue singlePort(RouterSnapshot s, String iface) { List<RouterSimpleQueue> queues = ownedPort(s, iface); requireUnique(queues); return queues.isEmpty() ? null : queues.getFirst(); }
    private List<RouterSimpleQueue> managedDeviceChildrenForPort(RouterSnapshot s, String iface) { return s.devices().stream().filter(d -> iface.equals(d.interfaceName())).flatMap(d -> ownedDevice(s, d.macAddress()).stream()).toList(); }
    private boolean isManagedDevice(RouterSnapshot s, RouterSimpleQueue q) { return s.devices().stream().anyMatch(d -> ManagedResourceIdentifier.isOwnedByDevice(q.comment(), d.macAddress())); }
    private RouterDevice uniqueDevice(RouterSnapshot s, String mac) { List<RouterDevice> matches = s.devices().stream().filter(d -> mac.equals(d.macAddress()) && d.ipAddress() != null).toList(); if (matches.size() != 1) throw error(ApiErrorCode.DEVICE_NOT_FOUND, HttpStatus.CONFLICT, "A lease atual do dispositivo não é única e válida."); return matches.getFirst(); }
    private void conflictForPort(RouterSnapshot s, String target, ManagedSimpleQueue expected, String iface) {
        for (RouterSimpleQueue q : s.simpleQueues()) {
            boolean expectedOwned = ManagedSimpleQueueSemantics.isOwnedPort(q, iface);
            if (expected.name().equals(q.name()) && !expectedOwned) throw foreignConflict();
            if (overlap(target, q.target()) && !expectedOwned && !isAnyManagedQueue(s, q)) throw foreignConflict();
        }
    }
    private void conflictForDevice(RouterSnapshot s, ManagedSimpleQueue expected, String mac) {
        for (RouterSimpleQueue q : s.simpleQueues()) {
            boolean expectedOwned = ManagedSimpleQueueSemantics.isOwnedDevice(q, mac);
            if (expected.name().equals(q.name()) && !expectedOwned) throw foreignConflict();
            if (overlap(expected.target(), q.target()) && !expectedOwned && !isAnyManagedQueue(s, q)) throw foreignConflict();
        }
    }
    private ApiException foreignConflict() { return error(ApiErrorCode.QUEUE_FOREIGN_CONFLICT, HttpStatus.CONFLICT, "Uma Simple Queue foreign ou dinâmica conflita com o recurso esperado."); }
    private boolean isAnyManagedQueue(RouterSnapshot s, RouterSimpleQueue q) { return s.devices().stream().anyMatch(d -> ManagedSimpleQueueSemantics.isOwnedDevice(q, d.macAddress())) || s.interfaces().stream().anyMatch(i -> ManagedSimpleQueueSemantics.isOwnedPort(q, i.name())); }
    private boolean overlap(String a, String b) { CidrRange one = CidrValidator.parseRange(a); CidrRange two = CidrValidator.parseRange(b); return one != null && two != null && one.overlaps(two); }
    private ManagedSimpleQueue withParent(RouterSimpleQueue q, String parent) { return new ManagedSimpleQueue(q.name(), q.comment(), q.target(), parent, q.maxLimit()); }
    private boolean same(RouterSimpleQueue q, ManagedSimpleQueue d) { return ManagedSimpleQueueSemantics.matchesDesired(q, d); }
    private String parent(RouterSimpleQueue q) { return ManagedSimpleQueueSemantics.parent(q); }
    private void requireSafe(RouterSimpleQueue q, String iface, boolean device) { if (!ManagedSimpleQueueSemantics.isSafeManagedQueue(q) || (iface != null && !ManagedSimpleQueueSemantics.isOwnedPort(q, iface)) || (device && q.dynamic())) throw error(ApiErrorCode.MANAGED_QUEUE_DRIFT, HttpStatus.CONFLICT, "A Simple Queue MTMGR divergiu da forma segura e não será reparada automaticamente."); }
    private boolean exceeds(SpeedLimit parent, SpeedLimit child) { return parent != null && child != null && ((parent.downloadBps() > 0 && child.downloadBps() > parent.downloadBps()) || (parent.uploadBps() > 0 && child.uploadBps() > parent.uploadBps())); }
    private void requireUnique(List<RouterSimpleQueue> queues) { if (queues.size() > 1) throw error(ApiErrorCode.QUEUE_OWNERSHIP_AMBIGUOUS, HttpStatus.CONFLICT, "Há mais de uma Simple Queue com ownership exato."); }
    private void requireManagedPort(ManagedPort port) { if (!port.enabled() || port.role() != com.mikrotikmanager.domain.ManagedPortRole.CLIENT || !CidrValidator.isValid(port.network())) throw error(ApiErrorCode.QUEUE_PARENT_INVALID, HttpStatus.CONFLICT, "A porta não é uma porta CLIENT com CIDR válido."); }
    private void validateLimit(SpeedLimit l) { if (!BandwidthLimitPolicy.isValidPhase5Limit(l)) throw error(ApiErrorCode.INVALID_SPEED_LIMIT, HttpStatus.BAD_REQUEST, "Limite inválido: nesta fase limite parcial ilimitado não é serializado sem validação física oficial."); }
    private void requireExecutablePreflight(OperationPlan plan) {
        boolean blockingConflict = plan.conflicts().stream().anyMatch(c -> c.severity() == PlanSeverity.BLOCKING);
        boolean blockingPrecondition = plan.preconditions().stream().anyMatch(p -> p.severity() == PlanSeverity.BLOCKING && !p.satisfied());
        if (!plan.readyForFutureExecution() || blockingConflict || blockingPrecondition)
            throw error(ApiErrorCode.QUEUE_FOREIGN_CONFLICT, HttpStatus.CONFLICT, "O preflight fresco não autorizou a alteração de Simple Queue.");
    }
    private boolean validId(String value) { return value != null && value.matches("\\*[A-Za-z0-9]+(?:[A-Za-z0-9]+)?"); }
    private void failVerify() { throw error(ApiErrorCode.QUEUE_POST_VERIFY_FAILED, HttpStatus.BAD_GATEWAY, "A leitura posterior não confirmou a Simple Queue desejada."); }
    private ApiException error(ApiErrorCode code, HttpStatus status, String message) { return new ApiException(code, status, message); }
    private String code(RuntimeException e) { return e instanceof ApiException api ? api.code().name() : "QUEUE_OPERATION_FAILED"; }
}
