package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.BlockDeviceIntent;
import com.mikrotikmanager.domain.BandwidthLimitPolicy;
import com.mikrotikmanager.domain.BlockingStrategy;
import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.domain.ManagedDeviceBlockRule;
import com.mikrotikmanager.domain.OperationIntent;
import com.mikrotikmanager.domain.OperationPlan;
import com.mikrotikmanager.domain.PlanChange;
import com.mikrotikmanager.domain.PlanConflict;
import com.mikrotikmanager.domain.PlanPrecondition;
import com.mikrotikmanager.domain.PlanSeverity;
import com.mikrotikmanager.domain.PlanState;
import com.mikrotikmanager.domain.PlanTarget;
import com.mikrotikmanager.domain.PlanWarning;
import com.mikrotikmanager.domain.ReconciliationFinding;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.ReconciliationResource;
import com.mikrotikmanager.domain.ReconciliationStatus;
import com.mikrotikmanager.domain.ResourceOwnership;
import com.mikrotikmanager.domain.RouterAddressListEntry;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.domain.RouterDhcpLease;
import com.mikrotikmanager.domain.RouterDhcpServer;
import com.mikrotikmanager.domain.RouterFirewallFilter;
import com.mikrotikmanager.domain.RouterInterface;
import com.mikrotikmanager.domain.RouterSimpleQueue;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.SetDeviceSpeedIntent;
import com.mikrotikmanager.domain.SetPortSpeedIntent;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.UnblockDeviceIntent;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds ephemeral, read-only dry-run plans from a fresh RouterOS snapshot.
 *
 * <p>This service deliberately knows no RouterOS mutation operation. A plan
 * describes a future intent and its observed preconditions; it is never an
 * authorization grant and remains non-executable.</p>
 */
@Service
public class OperationPlanningService {
    private static final Logger log = LoggerFactory.getLogger(OperationPlanningService.class);
    private static final String EXECUTION_REVALIDATION_REASON =
            "Este plano é somente preview; a execução real sempre reconstrói o estado e revalida ownership antes da escrita.";

    private final RouterSnapshotService snapshotService;
    private final ReconciliationService reconciliationService;
    private final ManagedPortRepository managedPortRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public OperationPlanningService(RouterSnapshotService snapshotService,
                                    ReconciliationService reconciliationService,
                                    ManagedPortRepository managedPortRepository,
                                    AuditService auditService) {
        this(snapshotService, reconciliationService, managedPortRepository, auditService, Clock.systemUTC());
    }

    OperationPlanningService(RouterSnapshotService snapshotService,
                             ReconciliationService reconciliationService,
                             ManagedPortRepository managedPortRepository,
                             AuditService auditService,
                             Clock clock) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService");
        this.managedPortRepository = Objects.requireNonNull(managedPortRepository, "managedPortRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Rebuilds the entire assessment from current RouterOS and SQLite state.
     * Client input supplies only an intent; it cannot assert ownership or
     * precondition results.
     */
    public OperationPlan plan(OperationIntent intent) {
        Objects.requireNonNull(intent, "intent");
        RouterSnapshot snapshot = snapshotService.capture();
        return planFromSnapshot(intent, snapshot);
    }

    /**
     * Rebuilds a plan from the exact snapshot captured immediately before an
     * execution attempt. The caller still owns the snapshot and never passes a
     * browser-supplied plan or fingerprint here.
     */
    public OperationPlan planFromSnapshot(OperationIntent intent, RouterSnapshot snapshot) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(snapshot, "snapshot");
        ReconciliationReport reconciliation = reconciliationService.analyze(snapshot);
        Map<String, ManagedPort> localPorts = managedPortRepository.findAll().stream()
                .collect(Collectors.toMap(ManagedPort::interfaceName, Function.identity(), (first, ignored) -> first));

        OperationPlan plan = switch (intent) {
            case BlockDeviceIntent block -> planBlock(block, snapshot, reconciliation, localPorts);
            case UnblockDeviceIntent unblock -> planUnblock(unblock, snapshot, reconciliation, localPorts);
            case SetDeviceSpeedIntent deviceSpeed -> planDeviceSpeed(deviceSpeed, snapshot, reconciliation, localPorts);
            case SetPortSpeedIntent portSpeed -> planPortSpeed(portSpeed, snapshot, reconciliation, localPorts);
        };
        log.info("Operation plan generated type={} target={} ready={} changeRequired={}",
                plan.operationType(), plan.target().identifier(), plan.readyForFutureExecution(), plan.changeRequired());
        auditPlan(plan);
        return plan;
    }

    /**
     * Stores only an operator-level summary. The plan, RouterOS snapshot,
     * credentials and raw transport data remain ephemeral.
     */
    private void auditPlan(OperationPlan plan) {
        auditService.record("OPERATION_PLAN_CREATED", "OPERATION_PLAN", plan.target().identifier(),
                "operationType=" + plan.operationType() + ";changeRequired=" + plan.changeRequired(),
                "readyForFutureExecution=" + plan.readyForFutureExecution() + ";executable=false",
                true, null);
    }

    public OperationPlan planBlockDevice(String macAddress) {
        return plan(new BlockDeviceIntent(macAddress));
    }

    public OperationPlan planUnblockDevice(String macAddress) {
        return plan(new UnblockDeviceIntent(macAddress));
    }

    public OperationPlan planDeviceSpeed(String macAddress, SpeedLimit requestedLimit) {
        return plan(new SetDeviceSpeedIntent(macAddress, requestedLimit));
    }

    public OperationPlan planPortSpeed(String interfaceName, SpeedLimit requestedLimit) {
        return plan(new SetPortSpeedIntent(interfaceName, requestedLimit));
    }

    private OperationPlan planBlock(BlockDeviceIntent intent, RouterSnapshot snapshot,
                                    ReconciliationReport reconciliation, Map<String, ManagedPort> localPorts) {
        DeviceContext device = resolveDevice(intent.macAddress(), snapshot, reconciliation, localPorts);
        List<PlanPrecondition> preconditions = devicePreconditions(device, snapshot);
        List<PlanWarning> warnings = new ArrayList<>();
        List<PlanConflict> conflicts = new ArrayList<>();
        boolean currentlyBlocked = device.currentlyBlocked();
        boolean changeRequired = device.hasUsableDevice() && !currentlyBlocked;
        preconditions.add(new PlanPrecondition("BLOCKING_STRATEGY_DECIDED",
                "A Fase 4 usa uma regra IPv4 /ip/firewall/filter por MAC, com ownership por comentário exato.", true,
                PlanSeverity.INFO));
        addFastTrackBlockWarning(snapshot, preconditions, warnings);
        addManagedBlockSemantics(device, preconditions, conflicts);
        addBlockResourceAmbiguity(device, preconditions, conflicts);

        List<PlanChange> changes;
        if (!device.hasUsableDevice()) {
            changes = List.of(new PlanChange("NO_ACTION", "DEVICE_BLOCK",
                    "O dispositivo não passou pelas validações de planejamento; nenhuma alteração será proposta."));
        } else if (device.hasSingleDesiredManagedRule()) {
            changes = List.of(new PlanChange("NO_CHANGE", "DEVICE_BLOCK",
                    "A regra MTMGR de bloqueio já existe com a semântica e a ordem esperadas; nenhuma escrita será enviada."));
        } else if (currentlyBlocked) {
            changes = List.of(new PlanChange("NO_ACTION", "DEVICE_BLOCK",
                    "Há um bloqueio existente que não pode ser adotado ou corrigido automaticamente."));
        } else {
            changes = List.of(new PlanChange("CREATE_FIREWALL_MAC_RULE", "FIREWALL_MAC_RULE",
                    "Criar uma regra drop forward por MAC antes da primeira regra forward estática e verificar a ordem depois."));
        }
        return build(intent, snapshot, device.target(), device.currentState(BlockingStrategy.FIREWALL_MAC_RULE),
                device.desiredBlockState(true), device.blockOwnership(), preconditions, warnings, conflicts, changes,
                device.hasUsableDevice() && !currentlyBlocked);
    }

    private OperationPlan planUnblock(UnblockDeviceIntent intent, RouterSnapshot snapshot,
                                      ReconciliationReport reconciliation, Map<String, ManagedPort> localPorts) {
        DeviceContext device = resolveDevice(intent.macAddress(), snapshot, reconciliation, localPorts);
        List<PlanPrecondition> preconditions = devicePreconditions(device, snapshot);
        List<PlanWarning> warnings = new ArrayList<>();
        List<PlanConflict> conflicts = new ArrayList<>();
        boolean currentlyBlocked = device.currentlyBlocked();
        boolean changeRequired = device.hasUsableDevice() && currentlyBlocked;
        preconditions.add(new PlanPrecondition("BLOCKING_STRATEGY_DECIDED",
                "A Fase 4 usa uma regra IPv4 /ip/firewall/filter por MAC, com ownership por comentário exato.", true,
                PlanSeverity.INFO));
        addFastTrackBlockWarning(snapshot, preconditions, warnings);
        addManagedBlockSemantics(device, preconditions, conflicts);
        if (!currentlyBlocked && device.hasUsableDevice()) {
            preconditions.add(new PlanPrecondition("NO_OWNED_BLOCK_TO_REMOVE",
                    "O dispositivo já está liberado no estado observado; nenhuma remoção será proposta.", true,
                    PlanSeverity.INFO));
        }
        addBlockResourceAmbiguity(device, preconditions, conflicts);

        List<PlanChange> changes;
        if (!device.hasUsableDevice()) {
            changes = List.of(new PlanChange("NO_ACTION", "DEVICE_BLOCK",
                    "O dispositivo não passou pelas validações de planejamento; nenhuma alteração será proposta."));
        } else if (device.hasSingleDesiredManagedRule()) {
            changes = List.of(new PlanChange("DELETE_FIREWALL_MAC_RULE", "FIREWALL_MAC_RULE",
                    "Remover somente a regra MTMGR de bloqueio resolvida no snapshot imediatamente anterior à escrita."));
        } else if (currentlyBlocked) {
            changes = List.of(new PlanChange("NO_ACTION", "DEVICE_BLOCK",
                    "O bloqueio observado não possui uma única regra MTMGR válida para remoção."));
        } else {
            changes = List.of(new PlanChange("NO_CHANGE", "DEVICE_BLOCK",
                    "O dispositivo já está liberado no estado observado; nenhuma alteração RouterOS será enviada."));
        }
        return build(intent, snapshot, device.target(), device.currentState(BlockingStrategy.FIREWALL_MAC_RULE),
                device.desiredBlockState(false), device.blockOwnership(), preconditions, warnings, conflicts, changes,
                changeRequired);
    }

    private OperationPlan planPortSpeed(SetPortSpeedIntent intent, RouterSnapshot snapshot,
                                        ReconciliationReport reconciliation, Map<String, ManagedPort> localPorts) {
        PortContext port = resolvePort(intent.interfaceName(), snapshot, reconciliation, localPorts);
        List<PlanPrecondition> preconditions = new ArrayList<>();
        List<PlanWarning> warnings = new ArrayList<>();
        List<PlanConflict> conflicts = new ArrayList<>();
        boolean requestedLimitValid = validLimit(intent.requestedLimit());

        addPortPreconditions(port, preconditions);
        addLimitPrecondition(intent.requestedLimit(), requestedLimitValid, preconditions);
        addQueueReconciliationFindings(port, preconditions, warnings, conflicts);
        addChildLimitPrecondition(port, intent.requestedLimit(), requestedLimitValid, snapshot.devices(), preconditions);
        addFastTrackWarning(snapshot, preconditions, warnings);

        SpeedLimit currentLimit = port.observedQueue() == null ? null : port.observedQueue().maxLimit();
        boolean changeRequired = requestedLimitValid && port.routerInterface() != null
                && !Objects.equals(currentLimit, intent.requestedLimit());
        List<PlanChange> changes = portSpeedChanges(port, requestedLimitValid, changeRequired, conflicts);
        return build(intent, snapshot, port.target(), port.currentState(currentLimit),
                port.desiredState(intent.requestedLimit()), port.ownership(), preconditions, warnings, conflicts, changes, changeRequired);
    }

    private OperationPlan planDeviceSpeed(SetDeviceSpeedIntent intent, RouterSnapshot snapshot,
                                          ReconciliationReport reconciliation, Map<String, ManagedPort> localPorts) {
        DeviceContext device = resolveDevice(intent.macAddress(), snapshot, reconciliation, localPorts);
        List<PlanPrecondition> preconditions = devicePreconditions(device, snapshot);
        List<PlanWarning> warnings = new ArrayList<>();
        List<PlanConflict> conflicts = new ArrayList<>();
        boolean requestedLimitValid = validLimit(intent.requestedLimit());
        addLimitPrecondition(intent.requestedLimit(), requestedLimitValid, preconditions);

        PortContext parentPort = resolvePort(device.interfaceName(), snapshot, reconciliation, localPorts);
        addPortPreconditions(parentPort, preconditions);
        addQueueReconciliationFindings(parentPort, preconditions, warnings, conflicts);
        addQueueReconciliationFindings(device.deviceQueueResource(), preconditions, warnings, conflicts);
        addDeviceParentLimitPrecondition(parentPort, intent.requestedLimit(), requestedLimitValid, preconditions);
        addFastTrackWarning(snapshot, preconditions, warnings);

        // Bandwidth ownership and observed speed are derived solely from the exact DEVICE_QUEUE.
        SpeedLimit currentLimit = device.deviceQueue() == null ? SpeedLimit.UNLIMITED : device.deviceQueue().maxLimit();
        boolean changeRequired = requestedLimitValid && device.hasUsableDevice()
                && !Objects.equals(currentLimit, intent.requestedLimit());
        if (changeRequired && currentLimit != null && !currentLimit.isUnlimited()
                && device.deviceQueueOwnership() != ResourceOwnership.MANAGED) {
            conflicts.add(new PlanConflict("UNOWNED_DEVICE_LIMIT", "DEVICE_QUEUE", device.target().displayName(),
                    device.ipAddress(), device.deviceQueueOwnership(),
                    "Há um limite de dispositivo observado sem ownership exato. A aplicação não irá adotá-lo ou sobrescrevê-lo.",
                    PlanSeverity.BLOCKING));
        } else if (!changeRequired && currentLimit != null && !currentLimit.isUnlimited()
                && device.deviceQueueOwnership() != ResourceOwnership.MANAGED) {
            warnings.add(new PlanWarning("UNOWNED_DEVICE_LIMIT_NO_OP",
                    "O limite atual não comprovadamente gerenciado já corresponde ao pedido; o plano não propõe alteração.",
                    PlanSeverity.WARNING));
        }

        List<PlanChange> changes = deviceSpeedChanges(device, requestedLimitValid, changeRequired, conflicts);
        return build(intent, snapshot, device.target(), device.currentSpeedState(currentLimit),
                device.desiredSpeedState(intent.requestedLimit()), device.deviceQueueOwnership(), preconditions, warnings, conflicts,
                changes, changeRequired);
    }

    private OperationPlan build(OperationIntent intent, RouterSnapshot snapshot, PlanTarget target,
                                PlanState currentState, PlanState desiredState, ResourceOwnership ownership,
                                List<PlanPrecondition> preconditions, List<PlanWarning> warnings,
                                List<PlanConflict> conflicts, List<PlanChange> changes, boolean changeRequired) {
        boolean ready = preconditions.stream()
                .noneMatch(precondition -> precondition.severity() == PlanSeverity.BLOCKING && !precondition.satisfied())
                && conflicts.stream().noneMatch(conflict -> conflict.severity() == PlanSeverity.BLOCKING);
        return new OperationPlan(
                UUID.randomUUID(),
                intent.operationType(),
                target,
                currentState,
                desiredState,
                ownership,
                preconditions,
                warnings,
                conflicts,
                changes,
                changeRequired,
                ready,
                false,
                EXECUTION_REVALIDATION_REASON,
                Instant.now(clock),
                snapshot.fingerprint()
        );
    }

    private DeviceContext resolveDevice(String rawMacAddress, RouterSnapshot snapshot, ReconciliationReport reconciliation,
                                        Map<String, ManagedPort> localPorts) {
        String normalizedMac = normalizeMac(rawMacAddress);
        if (normalizedMac == null) {
            return DeviceContext.invalid(rawMacAddress);
        }
        List<RouterDhcpLease> leases = snapshot.dhcpLeases().stream()
                .filter(lease -> normalizedMac.equals(normalizeMac(lease.macAddress())))
                .toList();
        List<RouterDevice> devices = snapshot.devices().stream()
                .filter(device -> normalizedMac.equals(normalizeMac(device.macAddress())))
                .toList();
        RouterDhcpLease lease = leases.size() == 1 ? leases.getFirst() : null;
        RouterDevice device = devices.size() == 1 ? devices.getFirst() : null;
        String interfaceName = device != null ? device.interfaceName() : lease == null ? null : lease.interfaceName();
        ManagedPort localPort = interfaceName == null ? null : localPorts.get(interfaceName);
        RouterInterface routerInterface = interfaceName == null ? null : snapshot.interfaces().stream()
                .filter(candidate -> interfaceName.equals(candidate.name()))
                .findFirst()
                .orElse(null);
        ReconciliationResource deviceQueueResource = reconciliation.resources().stream()
                .filter(resource -> "DEVICE_QUEUE".equals(resource.resourceType()) && normalizedMac.equals(resource.resourceKey()))
                .findFirst().orElse(null);
        List<RouterSimpleQueue> deviceQueues = snapshot.simpleQueues().stream()
                .filter(queue -> ManagedResourceIdentifier.isOwnedByDevice(queue.comment(), normalizedMac)).toList();
        RouterSimpleQueue deviceQueue = deviceQueues.size() == 1 ? deviceQueues.getFirst() : null;
        ResourceOwnership deviceQueueOwnership = deviceQueueResource == null ? ResourceOwnership.UNKNOWN : deviceQueueResource.ownership();
        List<BlockResource> blockResources = detectBlockResources(lease, device, snapshot, normalizedMac);
        List<RouterFirewallFilter> managedFirewallRules = snapshot.firewallFilters().stream()
                .filter(filter -> ManagedDeviceBlockRule.isOwned(filter, normalizedMac))
                .toList();
        return new DeviceContext(rawMacAddress, normalizedMac, leases, devices, lease, device, interfaceName, localPort,
                routerInterface, deviceQueueOwnership, deviceQueue, deviceQueueResource, blockResources, managedFirewallRules);
    }

    /**
     * Detects only already-observed blocking candidates. It never selects a
     * future blocking mechanism: DHCP block-access remains the only direct
     * lease signal, while firewall/address-list matching is deliberately
     * narrow and restricted to {@code chain=forward} {@code drop}/{@code reject}
     * so a {@code chain=input} rule protecting the MikroTik itself is never
     * mistaken for a client block. This exists solely to keep a future unblock
     * away from manual forward rules.
     */
    private List<BlockResource> detectBlockResources(RouterDhcpLease lease, RouterDevice device,
                                                      RouterSnapshot snapshot, String normalizedMac) {
        List<BlockResource> resources = new ArrayList<>();
        String ipAddress = lease != null && !blank(lease.ipAddress()) ? lease.ipAddress()
                : device == null ? null : device.ipAddress();
        if (lease != null && lease.blockAccess()) {
            resources.add(new BlockResource("DHCP_LEASE", "Lease DHCP", ipAddress,
                    ResourceOwnership.FOREIGN));
        }
        boolean hasActiveMacRule = snapshot.firewallFilters().stream()
                .filter(filter -> filter.srcMacAddress() != null)
                .filter(filter -> normalizedMac.equals(normalizeMac(filter.srcMacAddress())))
                .anyMatch(RouterFirewallFilter::isActiveForwardBlockingAction);
        if (device != null && device.blocked() && (lease == null || !lease.blockAccess()) && !hasActiveMacRule) {
            resources.add(new BlockResource("DEVICE_STATE", "Estado de bloqueio do dispositivo", ipAddress,
                    ResourceOwnership.FOREIGN));
        }
        snapshot.firewallFilters().stream()
                .filter(filter -> ManagedDeviceBlockRule.isOwned(filter, normalizedMac))
                .forEach(filter -> resources.add(new BlockResource("FIREWALL_MAC_RULE", "Regra MTMGR do dispositivo",
                        ipAddress, ResourceOwnership.MANAGED)));
        if (blank(ipAddress)) {
            return List.copyOf(resources);
        }
        snapshot.firewallFilters().stream()
                .filter(RouterFirewallFilter::isActiveForwardBlockingAction)
                .filter(filter -> filterTargetsIp(filter, ipAddress, snapshot.addressListEntries()))
                .filter(filter -> !ManagedDeviceBlockRule.isOwned(filter, normalizedMac))
                .forEach(filter -> resources.add(new BlockResource("FIREWALL_FILTER", "Regra de firewall", ipAddress,
                        blockingOwnership(filter.comment(), normalizedMac))));
        snapshot.firewallFilters().stream()
                .filter(RouterFirewallFilter::isActiveForwardBlockingAction)
                .filter(filter -> normalizedMac.equals(normalizeMac(filter.srcMacAddress())))
                .filter(filter -> !ManagedDeviceBlockRule.isOwned(filter, normalizedMac))
                .forEach(filter -> resources.add(new BlockResource("FIREWALL_MAC_RULE", "Regra manual por MAC", ipAddress,
                        blockingOwnership(filter.comment(), normalizedMac))));
        return List.copyOf(resources);
    }

    private boolean filterTargetsIp(RouterFirewallFilter filter, String ipAddress,
                                    List<RouterAddressListEntry> addressListEntries) {
        if (matchesExactHostAddress(filter.srcAddress(), ipAddress)) {
            return true;
        }
        if (blank(filter.srcAddressList())) {
            return false;
        }
        return addressListEntries.stream()
                .filter(entry -> !entry.disabled())
                .filter(entry -> filter.srcAddressList().equals(entry.listName()))
                .anyMatch(entry -> matchesExactHostAddress(entry.address(), ipAddress));
    }

    private boolean matchesExactHostAddress(String observedAddress, String ipAddress) {
        return ipAddress.equals(observedAddress) || (ipAddress + "/32").equals(observedAddress);
    }

    private ResourceOwnership blockingOwnership(String comment, String normalizedMac) {
        return ManagedResourceIdentifier.ownershipForDeviceComment(comment, normalizedMac);
    }

    private void addManagedBlockSemantics(DeviceContext device, List<PlanPrecondition> preconditions,
                                          List<PlanConflict> conflicts) {
        if (!device.hasUsableDevice()) {
            return;
        }
        if (device.managedFirewallRules().size() == 1) {
            if (device.hasSingleDesiredManagedRule()) {
                preconditions.add(new PlanPrecondition("MANAGED_BLOCK_RULE_DESIRED",
                        "A regra MTMGR observada possui chain, action, MAC, disabled e dynamic esperados.", true,
                        PlanSeverity.INFO));
                preconditions.add(new PlanPrecondition("RESOURCE_OWNERSHIP_CONFIRMED",
                        "A única regra de bloqueio possui o comentário de ownership exato do dispositivo.", true,
                        PlanSeverity.INFO));
            } else {
                preconditions.add(new PlanPrecondition("MANAGED_BLOCK_RULE_SEMANTICS",
                        "A regra MTMGR observada não diverge da forma FIREWALL_MAC_RULE esperada.", false,
                        PlanSeverity.BLOCKING));
                RouterFirewallFilter drifted = device.managedFirewallRules().getFirst();
                conflicts.add(new PlanConflict("MANAGED_BLOCK_RULE_DRIFT", "FIREWALL_MAC_RULE",
                        drifted.id(), device.ipAddress(), ResourceOwnership.MANAGED,
                        "A regra MTMGR existe, mas sua semântica divergiu. A aplicação não irá corrigi-la, moverá-la ou removê-la automaticamente.",
                        PlanSeverity.BLOCKING));
                preconditions.add(new PlanPrecondition("RESOURCE_OWNERSHIP_CONFIRMED",
                        "A regra possui comentário MTMGR, mas sua semântica divergente exige análise manual.", false,
                        PlanSeverity.BLOCKING));
            }
            return;
        }
        if (device.managedFirewallRules().size() > 1) {
            preconditions.add(new PlanPrecondition("RESOURCE_OWNERSHIP_CONFIRMED",
                    "Há mais de uma regra com o mesmo ownership; nenhuma delas será escolhida automaticamente.", false,
                    PlanSeverity.BLOCKING));
            return;
        }
        if (device.currentlyBlocked()) {
            preconditions.add(new PlanPrecondition("RESOURCE_OWNERSHIP_CONFIRMED",
                    "O bloqueio observado não possui ownership exato da regra FIREWALL_MAC_RULE.", false,
                    PlanSeverity.BLOCKING));
            BlockResource observed = device.primaryBlockResource();
            preconditions.add(new PlanPrecondition("NO_FOREIGN_BLOCK_CONFLICT",
                    "Não existe bloqueio estrangeiro ou não comprovado competindo com a regra MTMGR.", false,
                    PlanSeverity.BLOCKING));
            conflicts.add(new PlanConflict("UNOWNED_BLOCK_RESOURCE", observed.resourceType(), observed.displayName(),
                    observed.target(), observed.ownership(),
                    "O bloqueio observado não possui ownership exato da regra FIREWALL_MAC_RULE. A aplicação não irá removê-lo nem criar uma segunda regra.",
                    PlanSeverity.BLOCKING));
        } else {
            preconditions.add(new PlanPrecondition("RESOURCE_OWNERSHIP_CONFIRMED",
                    "Não há recurso de bloqueio existente para adotar; a regra criada receberá ownership MTMGR exato.", true,
                    PlanSeverity.INFO));
            preconditions.add(new PlanPrecondition("NO_FOREIGN_BLOCK_CONFLICT",
                    "Não existe bloqueio estrangeiro ou não comprovado competindo com a regra MTMGR.", true,
                    PlanSeverity.INFO));
        }
    }

    private void addBlockResourceAmbiguity(DeviceContext device, List<PlanPrecondition> preconditions,
                                           List<PlanConflict> conflicts) {
        boolean unambiguous = device.blockResources().size() <= 1;
        preconditions.add(new PlanPrecondition("BLOCK_RESOURCE_UNAMBIGUOUS",
                "Há no máximo um recurso RouterOS de bloqueio relacionado ao dispositivo.", unambiguous,
                PlanSeverity.BLOCKING));
        if (!unambiguous) {
            String code = device.managedFirewallRules().size() > 1 ? "AMBIGUOUS_OWNERSHIP" : "MULTIPLE_BLOCK_RESOURCES";
            conflicts.add(new PlanConflict(code, "BLOCK_RESOURCE", device.target().displayName(),
                    device.ipAddress(), device.blockOwnership(),
                    "Há múltiplos recursos de bloqueio observados; a aplicação não escolherá um deles para remover ou substituir.",
                    PlanSeverity.BLOCKING));
        }
    }

    private PortContext resolvePort(String rawInterfaceName, RouterSnapshot snapshot,
                                    ReconciliationReport reconciliation, Map<String, ManagedPort> localPorts) {
        String interfaceName = normalizeInterfaceName(rawInterfaceName);
        RouterInterface routerInterface = interfaceName == null ? null : snapshot.interfaces().stream()
                .filter(candidate -> interfaceName.equals(candidate.name()))
                .findFirst()
                .orElse(null);
        ManagedPort localPort = interfaceName == null ? null : localPorts.get(interfaceName);
        ReconciliationResource resource = interfaceName == null ? null : reconciliation.resources().stream()
                .filter(candidate -> "PORT_QUEUE".equals(candidate.resourceType()) || "SIMPLE_QUEUE".equals(candidate.resourceType()))
                .filter(candidate -> interfaceName.equals(candidate.resourceKey()))
                .findFirst()
                .orElse(null);
        List<RouterSimpleQueue> ownedQueues = interfaceName == null ? List.of() : snapshot.simpleQueues().stream()
                .filter(queue -> ManagedResourceIdentifier.isOwnedByPort(queue.comment(), interfaceName))
                .toList();
        RouterSimpleQueue observedQueue = ownedQueues.size() == 1 ? ownedQueues.getFirst() : null;
        ResourceOwnership ownership = resource == null ? ResourceOwnership.UNKNOWN : resource.ownership();
        return new PortContext(rawInterfaceName, interfaceName, routerInterface, localPort, resource, observedQueue, ownership);
    }

    private List<PlanPrecondition> devicePreconditions(DeviceContext device, RouterSnapshot snapshot) {
        List<PlanPrecondition> preconditions = new ArrayList<>();
        preconditions.add(new PlanPrecondition("DEVICE_HAS_MAC", "O endereço MAC informado é válido.", device.normalizedMac() != null,
                PlanSeverity.BLOCKING));
        preconditions.add(new PlanPrecondition("DEVICE_LEASE_UNAMBIGUOUS",
                "Há exatamente uma lease DHCP correspondente ao dispositivo.", device.leases().size() == 1, PlanSeverity.BLOCKING));
        preconditions.add(new PlanPrecondition("DEVICE_EXISTS",
                "O dispositivo está correlacionado com segurança a partir da lease DHCP atual.", device.devices().size() == 1,
                PlanSeverity.BLOCKING));
        preconditions.add(new PlanPrecondition("DEVICE_HAS_IP", "A lease do dispositivo possui endereço IP observado.",
                device.ipAddress() != null, PlanSeverity.BLOCKING));
        preconditions.add(new PlanPrecondition("LEASE_BOUND", "A lease DHCP observada está no estado bound.",
                device.lease() != null && device.lease().isBound(), PlanSeverity.BLOCKING));
        preconditions.add(new PlanPrecondition("DHCP_SERVER_KNOWN",
                "O servidor DHCP da lease está presente e habilitado no snapshot.", knownDhcpServer(device.lease(), snapshot.dhcpServers()),
                PlanSeverity.BLOCKING));
        return preconditions;
    }

    private boolean knownDhcpServer(RouterDhcpLease lease, List<RouterDhcpServer> servers) {
        if (lease == null || blank(lease.server())) {
            return false;
        }
        return servers.stream().anyMatch(server -> lease.server().equals(server.name()) && !server.disabled()
                && (blank(lease.interfaceName()) || lease.interfaceName().equals(server.interfaceName())));
    }

    private void addPortPreconditions(PortContext port, List<PlanPrecondition> preconditions) {
        preconditions.add(new PlanPrecondition("PORT_EXISTS", "A interface alvo existe no snapshot RouterOS.",
                port.routerInterface() != null, PlanSeverity.BLOCKING));
        boolean managed = port.localPort() != null && port.localPort().enabled()
                && port.localPort().role() == ManagedPortRole.CLIENT;
        preconditions.add(new PlanPrecondition("PORT_IS_MANAGED",
                "A interface possui metadata local de porta CLIENT habilitada.", managed, PlanSeverity.BLOCKING));
        boolean network = managed && !blank(port.localPort().network()) && CidrValidator.isValid(port.localPort().network());
        preconditions.add(new PlanPrecondition("PORT_HAS_NETWORK",
                "A porta CLIENT possui CIDR local válido configurado.", network, PlanSeverity.BLOCKING));
    }

    private void addLimitPrecondition(SpeedLimit requested, boolean valid, List<PlanPrecondition> preconditions) {
        preconditions.add(new PlanPrecondition("REQUESTED_LIMIT_VALID",
                "O limite solicitado é não negativo, não ultrapassa o máximo suportado e zero representa ilimitado.", valid,
                PlanSeverity.BLOCKING));
    }

    private void addQueueReconciliationFindings(PortContext port, List<PlanPrecondition> preconditions,
                                                List<PlanWarning> warnings, List<PlanConflict> conflicts) {
        addQueueReconciliationFindings(port.reconciliationResource(), preconditions, warnings, conflicts);
    }

    private void addQueueReconciliationFindings(ReconciliationResource resource, List<PlanPrecondition> preconditions,
                                                List<PlanWarning> warnings, List<PlanConflict> conflicts) {
        boolean available = resource != null;
        preconditions.add(new PlanPrecondition("QUEUE_RECONCILIATION_AVAILABLE",
                "A fila futura da porta foi analisada a partir do mesmo snapshot.", available, PlanSeverity.BLOCKING));
        if (!available) {
            return;
        }

        boolean ownershipUnambiguous = resource.status() != ReconciliationStatus.AMBIGUOUS_OWNERSHIP;
        preconditions.add(new PlanPrecondition("RESOURCE_OWNERSHIP_UNAMBIGUOUS",
                "Não há múltiplas Simple Queues com o mesmo comentário de ownership exato.", ownershipUnambiguous,
                PlanSeverity.BLOCKING));
        boolean noForeignConflict = !resource.conflict()
                && resource.status() != ReconciliationStatus.CONFLICT
                && resource.status() != ReconciliationStatus.FOREIGN;
        preconditions.add(new PlanPrecondition("NO_FOREIGN_CONFLICT",
                "Nenhuma configuração manual ou não comprovadamente gerenciada conflita com a fila futura.", noForeignConflict,
                PlanSeverity.BLOCKING));

        for (ReconciliationFinding finding : resource.findings()) {
            if (finding.severity() == PlanSeverity.BLOCKING) {
                preconditions.add(new PlanPrecondition(finding.code(), finding.description(), false, PlanSeverity.BLOCKING));
            } else {
                warnings.add(new PlanWarning(finding.code(), finding.description(), finding.severity()));
            }
        }
        if (!noForeignConflict || resource.status() == ReconciliationStatus.AMBIGUOUS_OWNERSHIP) {
            conflicts.add(new PlanConflict(resource.status().name(), resource.resourceType(),
                    valueOr(resource.observedName(), resource.displayName()), resource.observedTarget(), resource.ownership(),
                    conflictDescription(resource), PlanSeverity.BLOCKING));
        }
    }

    private String conflictDescription(ReconciliationResource resource) {
        return resource.findings().stream()
                .filter(finding -> finding.severity() == PlanSeverity.BLOCKING)
                .map(ReconciliationFinding::description)
                .findFirst()
                .orElse("A configuração RouterOS observada conflita com a operação futura e não será alterada automaticamente.");
    }

    private void addChildLimitPrecondition(PortContext port, SpeedLimit requested, boolean requestedValid,
                                           List<RouterDevice> devices, List<PlanPrecondition> preconditions) {
        if (!requestedValid || port.interfaceName() == null) {
            preconditions.add(new PlanPrecondition("CHILD_LIMITS_WITHIN_PORT",
                    "Os limites atuais dos dispositivos não podem ser avaliados sem um limite e uma porta válidos.", false,
                    PlanSeverity.BLOCKING));
            return;
        }
        List<RouterDevice> exceeding = devices.stream()
                .filter(device -> port.interfaceName().equals(device.interfaceName()))
                .filter(device -> exceeds(requested, device.speedLimit()))
                .toList();
        preconditions.add(new PlanPrecondition("CHILD_LIMITS_WITHIN_PORT",
                exceeding.isEmpty()
                        ? "Todos os limites de dispositivos observados cabem no limite solicitado para a porta."
                        : "Um ou mais limites de dispositivos observados ultrapassam o limite solicitado para a porta.",
                exceeding.isEmpty(), PlanSeverity.BLOCKING));
    }

    private void addDeviceParentLimitPrecondition(PortContext parent, SpeedLimit requested, boolean requestedValid,
                                                  List<PlanPrecondition> preconditions) {
        SpeedLimit parentLimit = parent.observedQueue() == null ? null : parent.observedQueue().maxLimit();
        boolean parentReadable = parentLimit != null;
        preconditions.add(new PlanPrecondition("PARENT_PORT_LIMIT_KNOWN",
                parentReadable
                        ? "O limite da porta pai foi lido da Simple Queue explicitamente gerenciada."
                        : "Não há limite de porta pai legível; uma futura execução deverá revalidar esta invariável.",
                parentReadable, PlanSeverity.WARNING));
        boolean withinParent = requestedValid && (parentLimit == null || !exceeds(parentLimit, requested));
        preconditions.add(new PlanPrecondition("LIMIT_WITHIN_PARENT",
                withinParent
                        ? "O limite solicitado para o dispositivo não ultrapassa o limite finito da porta pai."
                        : "O limite solicitado para o dispositivo ultrapassa o limite finito da porta pai.",
                withinParent, PlanSeverity.BLOCKING));
    }

    private void addFastTrackWarning(RouterSnapshot snapshot, List<PlanPrecondition> preconditions,
                                     List<PlanWarning> warnings) {
        boolean fastTrackActive = snapshot.fastTrackDetected();
        preconditions.add(new PlanPrecondition("FASTTRACK_REVIEWED",
                fastTrackActive
                        ? "FastTrack ativo foi detectado e exige revisão antes de uma futura operação de banda."
                        : "Nenhuma regra FastTrack ativa foi detectada neste snapshot.",
                !fastTrackActive, fastTrackActive ? PlanSeverity.BLOCKING : PlanSeverity.INFO));
        if (fastTrackActive) {
            warnings.add(new PlanWarning("FASTTRACK_BYPASSES_SIMPLE_QUEUE",
                    "FastTrack ativo impede a aplicação segura deste limite por Simple Queue nesta fase.", PlanSeverity.BLOCKING));
        }
    }

    private void addFastTrackBlockWarning(RouterSnapshot snapshot, List<PlanPrecondition> preconditions,
                                          List<PlanWarning> warnings) {
        boolean fastTrackActive = snapshot.fastTrackDetected();
        preconditions.add(new PlanPrecondition("FASTTRACK_EXISTING_CONNECTIONS",
                fastTrackActive
                        ? "Conexões já FastTracked podem continuar até serem encerradas ou expirarem no RouterOS."
                        : "Nenhuma regra FastTrack ativa foi detectada neste snapshot.",
                !fastTrackActive, fastTrackActive ? PlanSeverity.WARNING : PlanSeverity.INFO));
        if (fastTrackActive) {
            warnings.add(new PlanWarning("FASTTRACK_EXISTING_CONNECTIONS",
                    "O dispositivo foi bloqueado para novos fluxos encaminhados. Conexões que já estavam FastTracked podem continuar até serem encerradas ou expirarem no RouterOS.",
                    PlanSeverity.WARNING));
        }
    }

    private List<PlanChange> portSpeedChanges(PortContext port, boolean limitValid, boolean changeRequired,
                                              List<PlanConflict> conflicts) {
        if (!limitValid || port.routerInterface() == null) {
            return List.of(new PlanChange("NO_ACTION", "SIMPLE_QUEUE",
                    "O pedido não passou pelas validações de planejamento; nenhuma alteração será proposta."));
        }
        if (!changeRequired) {
            return List.of(new PlanChange("NO_CHANGE", "SIMPLE_QUEUE",
                    "O max-limit observado já corresponde ao limite solicitado; nenhuma alteração RouterOS será enviada."));
        }
        if (!conflicts.isEmpty()) {
            return List.of(new PlanChange("NO_ACTION", "SIMPLE_QUEUE",
                    "Há conflito de ownership; a aplicação não irá adotar, substituir ou alterar a fila observada."));
        }
        if (port.reconciliationResource() != null && port.reconciliationResource().status() == ReconciliationStatus.MISSING) {
            return List.of(new PlanChange("CREATE_PORT_QUEUE", "PORT_QUEUE",
                    "Criar a fila MTMGR da porta e, se necessário, reparentar filhos MTMGR após a criação."));
        }
        return List.of(new PlanChange("UPDATE_PORT_QUEUE", "PORT_QUEUE",
                "Atualizar somente a fila MTMGR exata após fresh snapshot e revalidação."));
    }

    private List<PlanChange> deviceSpeedChanges(DeviceContext device, boolean limitValid, boolean changeRequired,
                                                List<PlanConflict> conflicts) {
        if (!limitValid || !device.hasUsableDevice()) {
            return List.of(new PlanChange("NO_ACTION", "DEVICE_SPEED",
                    "O pedido não passou pelas validações de planejamento; nenhuma alteração será proposta."));
        }
        if (!changeRequired) {
            return List.of(new PlanChange("NO_CHANGE", "DEVICE_SPEED",
                    "O limite observado já corresponde ao limite solicitado; nenhuma alteração RouterOS será enviada."));
        }
        if (!conflicts.isEmpty()) {
            return List.of(new PlanChange("NO_ACTION", "DEVICE_SPEED",
                    "Há conflito de ownership; a aplicação não irá adotar ou sobrescrever o recurso observado."));
        }
        return List.of(new PlanChange("UPDATE_DEVICE_QUEUE", "DEVICE_QUEUE",
                "Atualizar somente a fila MTMGR exata após fresh snapshot e revalidação."));
    }

    private boolean validLimit(SpeedLimit requested) {
        return BandwidthLimitPolicy.isValidPhase5Limit(requested);
    }

    /** Returns whether a finite parent limit is exceeded by a child/requested limit. */
    private boolean exceeds(SpeedLimit parent, SpeedLimit child) {
        if (parent == null || child == null || parent.isUnlimited()) {
            return false;
        }
        return (parent.downloadBps() > 0 && child.downloadBps() > parent.downloadBps())
                || (parent.uploadBps() > 0 && child.uploadBps() > parent.uploadBps());
    }

    private String normalizeMac(String candidate) {
        try {
            return ManagedResourceIdentifier.normalizeMac(candidate);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String normalizeInterfaceName(String candidate) {
        return blank(candidate) ? null : candidate;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String valueOr(String primary, String fallback) {
        return blank(primary) ? fallback : primary;
    }

    private record DeviceContext(
            String requestedMac,
            String normalizedMac,
            List<RouterDhcpLease> leases,
            List<RouterDevice> devices,
            RouterDhcpLease lease,
            RouterDevice routerDevice,
            String interfaceName,
            ManagedPort localPort,
            RouterInterface routerInterface,
            ResourceOwnership deviceQueueOwnership,
            RouterSimpleQueue deviceQueue,
            ReconciliationResource deviceQueueResource,
            List<BlockResource> blockResources,
            List<RouterFirewallFilter> managedFirewallRules
    ) {
        private static DeviceContext invalid(String requestedMac) {
            return new DeviceContext(requestedMac, null, List.of(), List.of(), null, null,
                    null, null, null, ResourceOwnership.UNKNOWN, null, null, List.of(), List.of());
        }

        private boolean hasUsableDevice() {
            return normalizedMac != null && lease != null && routerDevice != null;
        }

        private boolean currentlyBlocked() {
            return !blockResources.isEmpty();
        }

        private ResourceOwnership blockOwnership() {
            if (blockResources.isEmpty()) {
                return ResourceOwnership.UNKNOWN;
            }
            if (blockResources.stream().allMatch(resource -> resource.ownership() == ResourceOwnership.MANAGED)) {
                return ResourceOwnership.MANAGED;
            }
            if (blockResources.stream().anyMatch(resource -> resource.ownership() == ResourceOwnership.FOREIGN)) {
                return ResourceOwnership.FOREIGN;
            }
            return ResourceOwnership.UNKNOWN;
        }

        private boolean hasSingleDesiredManagedRule() {
            return managedFirewallRules.size() == 1
                    && ManagedDeviceBlockRule.isExactDesiredRule(managedFirewallRules.getFirst(), normalizedMac);
        }

        private BlockResource primaryBlockResource() {
            return blockResources.isEmpty()
                    ? new BlockResource("BLOCK_RESOURCE", "Recurso de bloqueio não identificado", ipAddress(), ResourceOwnership.UNKNOWN)
                    : blockResources.getFirst();
        }

        private String displayName() {
            if (routerDevice != null && !blank(routerDevice.hostname())) {
                return routerDevice.hostname();
            }
            return normalizedMac == null ? "Dispositivo não encontrado" : normalizedMac;
        }

        private String ipAddress() {
            if (lease != null && !blank(lease.ipAddress())) {
                return lease.ipAddress();
            }
            return routerDevice == null || blank(routerDevice.ipAddress()) ? null : routerDevice.ipAddress();
        }

        private PlanTarget target() {
            String identifier = normalizedMac == null ? "invalid-mac" : normalizedMac;
            return new PlanTarget(identifier, displayName(), normalizedMac, interfaceName);
        }

        private PlanState currentState(BlockingStrategy blockingStrategy) {
            return new PlanState(displayName(), normalizedMac, ipAddress(), interfaceName,
                    localPort == null ? null : localPort.network(), routerDevice == null ? null : routerDevice.speedLimit(),
                    routerDevice == null && lease == null ? null : currentlyBlocked(), blockingStrategy);
        }

        private PlanState desiredBlockState(boolean blocked) {
            return new PlanState(displayName(), normalizedMac, ipAddress(), interfaceName,
                    localPort == null ? null : localPort.network(), routerDevice == null ? null : routerDevice.speedLimit(),
                    blocked, BlockingStrategy.FIREWALL_MAC_RULE);
        }

        private PlanState desiredSpeedState(SpeedLimit requestedLimit) {
            return new PlanState(displayName(), normalizedMac, ipAddress(), interfaceName,
                    localPort == null ? null : localPort.network(), requestedLimit,
                    routerDevice == null && lease == null ? null : currentlyBlocked(), null);
        }

        private PlanState currentSpeedState(SpeedLimit currentLimit) {
            return new PlanState(displayName(), normalizedMac, ipAddress(), interfaceName,
                    localPort == null ? null : localPort.network(), currentLimit,
                    routerDevice == null && lease == null ? null : currentlyBlocked(), null);
        }
    }

    /** Safe, display-only description of a previously observed block candidate. */
    private record BlockResource(String resourceType, String displayName, String target, ResourceOwnership ownership) {
    }

    private record PortContext(
            String requestedInterfaceName,
            String interfaceName,
            RouterInterface routerInterface,
            ManagedPort localPort,
            ReconciliationResource reconciliationResource,
            RouterSimpleQueue observedQueue,
            ResourceOwnership ownership
    ) {
        private PlanTarget target() {
            String identifier = interfaceName == null ? "invalid-interface" : interfaceName;
            String displayName = localPort != null && !blank(localPort.friendlyName())
                    ? localPort.friendlyName() : identifier;
            return new PlanTarget(identifier, displayName, null, interfaceName);
        }

        private PlanState currentState(SpeedLimit currentLimit) {
            return new PlanState(target().displayName(), null, null, interfaceName,
                    localPort == null ? null : localPort.network(), currentLimit, null, null);
        }

        private PlanState desiredState(SpeedLimit requestedLimit) {
            return new PlanState(target().displayName(), null, null, interfaceName,
                    localPort == null ? null : localPort.network(), requestedLimit, null, null);
        }
    }
}
