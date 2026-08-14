package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.domain.ManagedSimpleQueue;
import com.mikrotikmanager.domain.ManagedSimpleQueueSemantics;
import com.mikrotikmanager.domain.PlanSeverity;
import com.mikrotikmanager.domain.ReconciliationFinding;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.ReconciliationResource;
import com.mikrotikmanager.domain.ReconciliationStatus;
import com.mikrotikmanager.domain.ReconciliationSummary;
import com.mikrotikmanager.domain.ResourceOwnership;
import com.mikrotikmanager.domain.RouterSimpleQueue;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.RouterDevice;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.persistence.ManagedPortRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Compares local metadata with a single read-only snapshot. This service is
 * deliberately observational: it has no repository save and no gateway
 * mutation call.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final RouterSnapshotService snapshotService;
    private final ManagedPortRepository managedPortRepository;
    private final Clock clock;

    @Autowired
    public ReconciliationService(RouterSnapshotService snapshotService, ManagedPortRepository managedPortRepository) {
        this(snapshotService, managedPortRepository, Clock.systemUTC());
    }

    ReconciliationService(RouterSnapshotService snapshotService, ManagedPortRepository managedPortRepository, Clock clock) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.managedPortRepository = Objects.requireNonNull(managedPortRepository, "managedPortRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReconciliationReport analyze() {
        return analyze(snapshotService.capture());
    }

    public ReconciliationReport analyze(RouterSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<ManagedPort> managedPorts = managedPortRepository.findAll();
        List<ReconciliationResource> resources = new ArrayList<>(managedPorts.stream()
                .sorted(Comparator.comparing(ManagedPort::interfaceName))
                .map(port -> reconcilePortQueue(port, snapshot.simpleQueues()))
                .toList());
        snapshot.devices().stream().sorted(Comparator.comparing(RouterDevice::macAddress))
                .forEach(device -> resources.add(reconcileDeviceQueue(device, snapshot.simpleQueues(), managedPorts)));
        ReconciliationSummary summary = summarize(resources);
        log.info("Reconciliation completed managed={} conflicts={} drifted={} missing={}",
                summary.managed(), summary.conflicts(), summary.drifted(), summary.missing());
        return new ReconciliationReport(Instant.now(clock), snapshot.fingerprint(), snapshot.interfaces().size(),
                snapshot.dhcpServers().size(), snapshot.dhcpLeases().size(), snapshot.simpleQueues().size(),
                snapshot.fastTrackDetected(), resources, summary);
    }

    private ReconciliationResource reconcilePortQueue(ManagedPort port, List<RouterSimpleQueue> queues) {
        String expectedName = expectedQueueName(port.interfaceName());
        boolean applicable = port.enabled() && port.role() == ManagedPortRole.CLIENT
                && port.network() != null && !port.network().isBlank();
        if (!applicable) {
            return new ReconciliationResource("PORT_QUEUE", port.interfaceName(), "Queue da porta " + port.interfaceName(),
                    ResourceOwnership.UNKNOWN, ReconciliationStatus.NOT_APPLICABLE, expectedName, port.network(), null, null,
                    false, List.of(new ReconciliationFinding("PORT_QUEUE_NOT_APPLICABLE",
                    "A porta não é uma porta CLIENT habilitada com CIDR local configurado.", PlanSeverity.INFO)));
        }

        List<RouterSimpleQueue> owned = queues.stream()
                .filter(queue -> ManagedResourceIdentifier.isOwnedByPort(queue.comment(), port.interfaceName()))
                .toList();
        List<RouterSimpleQueue> foreignConflicts = queues.stream()
                .filter(queue -> !ManagedResourceIdentifier.isOwnedByPort(queue.comment(), port.interfaceName()))
                .filter(queue -> !isExactManagedDeviceQueue(queue))
                .filter(queue -> foreignQueueConflicts(port, expectedName, queue))
                .toList();

        if (owned.size() > 1) {
            return new ReconciliationResource("PORT_QUEUE", port.interfaceName(), "Queue da porta " + port.interfaceName(),
                    ResourceOwnership.MANAGED, ReconciliationStatus.AMBIGUOUS_OWNERSHIP, expectedName, port.network(),
                    owned.getFirst().name(), owned.getFirst().target(), true, List.of(new ReconciliationFinding(
                    "DUPLICATE_MANAGED_QUEUE", "Há mais de uma Simple Queue com o comentário de ownership exato desta porta.",
                    PlanSeverity.BLOCKING)));
        }

        if (!foreignConflicts.isEmpty()) {
            RouterSimpleQueue conflict = foreignConflicts.getFirst();
            ResourceOwnership ownership = ManagedResourceIdentifier.ownershipForPortComment(conflict.comment(), port.interfaceName());
            return new ReconciliationResource("PORT_QUEUE", port.interfaceName(), "Queue da porta " + port.interfaceName(),
                    ownership, ReconciliationStatus.CONFLICT, expectedName, port.network(), conflict.name(), conflict.target(),
                    true, List.of(new ReconciliationFinding("FOREIGN_QUEUE_CONFLICT",
                    "Existe uma Simple Queue manual ou não comprovadamente gerenciada que usa o nome, target sobreposto "
                            + "ou um target relacionado que não pôde ser interpretado com segurança. A aplicação não irá "
                            + "adotá-la nem alterá-la automaticamente.", PlanSeverity.BLOCKING)));
        }

        if (owned.isEmpty()) {
            return new ReconciliationResource("PORT_QUEUE", port.interfaceName(), "Queue da porta " + port.interfaceName(),
                    ResourceOwnership.UNKNOWN, ReconciliationStatus.MISSING, expectedName, port.network(), null, null, false,
                    List.of(new ReconciliationFinding("MANAGED_QUEUE_MISSING",
                    "Nenhuma Simple Queue com ownership exato foi observada para esta porta.", PlanSeverity.INFO)));
        }

        RouterSimpleQueue queue = owned.getFirst();
        List<ReconciliationFinding> drift = new ArrayList<>();
        if (!expectedName.equals(queue.name())) {
            drift.add(new ReconciliationFinding("QUEUE_NAME_DRIFT", "O nome observado diverge da convenção futura da aplicação.",
                    PlanSeverity.BLOCKING));
        }
        if (!port.network().equals(queue.target())) {
            drift.add(new ReconciliationFinding("QUEUE_TARGET_DRIFT", "O target observado diverge do CIDR local da porta.",
                    PlanSeverity.BLOCKING));
        }
        if (!"none".equals(ManagedSimpleQueueSemantics.parent(queue))) {
            drift.add(new ReconciliationFinding("QUEUE_PARENT_DRIFT",
                    "A fila da porta deve ser uma Simple Queue raiz, sem parent configurado.", PlanSeverity.BLOCKING));
        }
        if (queue.disabled()) {
            drift.add(new ReconciliationFinding("QUEUE_DISABLED", "A Simple Queue gerenciada está desabilitada.", PlanSeverity.WARNING));
        }
        if (queue.dynamic()) {
            drift.add(new ReconciliationFinding("QUEUE_DYNAMIC", "A Simple Queue gerenciada é dinâmica e não é segura para futura gestão.",
                    PlanSeverity.BLOCKING));
        }
        if (queue.maxLimit() == null) {
            drift.add(new ReconciliationFinding("QUEUE_LIMIT_UNREADABLE", "O max-limit observado não pôde ser interpretado com segurança.",
                    PlanSeverity.BLOCKING));
        }
        addQueueSemanticDrift(queue, drift);
        ReconciliationStatus status = drift.isEmpty() ? ReconciliationStatus.IN_SYNC : ReconciliationStatus.DRIFTED;
        return new ReconciliationResource("PORT_QUEUE", port.interfaceName(), "Queue da porta " + port.interfaceName(),
                ResourceOwnership.MANAGED, status, expectedName, port.network(), queue.name(), queue.target(), false, drift);
    }

    private ReconciliationResource reconcileDeviceQueue(RouterDevice device, List<RouterSimpleQueue> queues,
                                                        List<ManagedPort> managedPorts) {
        String mac = device.macAddress(); String expectedName = ManagedResourceIdentifier.expectedDeviceQueueName(mac);
        List<RouterSimpleQueue> owned = queues.stream().filter(q -> ManagedResourceIdentifier.isOwnedByDevice(q.comment(), mac)).toList();
        List<RouterSimpleQueue> conflicts = queues.stream().filter(q -> !ManagedResourceIdentifier.isOwnedByDevice(q.comment(), mac))
                .filter(q -> !ManagedResourceIdentifier.isOwnedByPort(q.comment(), device.interfaceName()))
                .filter(q -> expectedName.equals(q.name()) || targetOverlaps(device.ipAddress() == null ? "" : device.ipAddress() + "/32", q.target())).toList();
        if (owned.size() > 1) return resource("DEVICE_QUEUE", mac, expectedName, device.ipAddress(), ResourceOwnership.MANAGED, ReconciliationStatus.AMBIGUOUS_OWNERSHIP, owned.getFirst(), true, "DUPLICATE_MANAGED_QUEUE", "Há mais de uma fila MTMGR para o dispositivo.", PlanSeverity.BLOCKING);
        if (!conflicts.isEmpty()) return resource("DEVICE_QUEUE", mac, expectedName, device.ipAddress(), ResourceOwnership.FOREIGN, ReconciliationStatus.CONFLICT, conflicts.getFirst(), true, "FOREIGN_QUEUE_CONFLICT", "Há fila foreign ou dinâmica sobreposta ao IP do dispositivo.", PlanSeverity.BLOCKING);
        if (owned.isEmpty()) return new ReconciliationResource("DEVICE_QUEUE", mac, "Queue do dispositivo " + mac, ResourceOwnership.UNKNOWN, ReconciliationStatus.MISSING, expectedName, device.ipAddress() == null ? null : device.ipAddress() + "/32", null, null, false, List.of(new ReconciliationFinding("MANAGED_QUEUE_MISSING", "Nenhuma fila MTMGR do dispositivo foi observada.", PlanSeverity.INFO)));
        RouterSimpleQueue queue = owned.getFirst(); List<ReconciliationFinding> drift = new ArrayList<>();
        if (!expectedName.equals(queue.name())) drift.add(new ReconciliationFinding("QUEUE_NAME_DRIFT", "Nome determinístico inesperado.", PlanSeverity.BLOCKING));
        if (device.ipAddress() == null || !(device.ipAddress() + "/32").equals(queue.target())) drift.add(new ReconciliationFinding("TARGET_DRIFT", "O target da fila diverge da lease DHCP atual.", PlanSeverity.BLOCKING));
        String expectedParent = expectedDeviceParent(device, queues, managedPorts);
        if (!expectedParent.equals(ManagedSimpleQueueSemantics.parent(queue))) {
            drift.add(new ReconciliationFinding("QUEUE_PARENT_DRIFT",
                    "O parent observado diverge da hierarquia Simple Queue segura para a porta atual do dispositivo.",
                    PlanSeverity.BLOCKING));
        }
        addQueueSemanticDrift(queue, drift);
        return new ReconciliationResource("DEVICE_QUEUE", mac, "Queue do dispositivo " + mac, ResourceOwnership.MANAGED, drift.isEmpty() ? ReconciliationStatus.IN_SYNC : ReconciliationStatus.DRIFTED, expectedName, device.ipAddress() == null ? null : device.ipAddress() + "/32", queue.name(), queue.target(), false, drift);
    }

    /** A device inherits a parent only from an already exact, safe PORT_QUEUE. */
    private String expectedDeviceParent(RouterDevice device, List<RouterSimpleQueue> queues,
                                        List<ManagedPort> managedPorts) {
        ManagedPort port = managedPorts.stream()
                .filter(candidate -> candidate.interfaceName().equals(device.interfaceName()))
                .filter(candidate -> candidate.enabled() && candidate.role() == ManagedPortRole.CLIENT)
                .filter(candidate -> CidrValidator.isValid(candidate.network()))
                .findFirst().orElse(null);
        if (port == null) return "none";
        List<RouterSimpleQueue> parents = queues.stream()
                .filter(queue -> ManagedSimpleQueueSemantics.isOwnedPort(queue, port.interfaceName()))
                .toList();
        if (parents.size() != 1) return "none";
        RouterSimpleQueue parent = parents.getFirst();
        ManagedSimpleQueue desired = ManagedSimpleQueue.port(port.interfaceName(), port.network(), parent.maxLimit());
        return ManagedSimpleQueueSemantics.matchesDesired(parent, desired) ? desired.name() : "none";
    }
    private ReconciliationResource resource(String type, String key, String expectedName, String ip, ResourceOwnership ownership, ReconciliationStatus status, RouterSimpleQueue observed, boolean conflict, String code, String description, PlanSeverity severity) {
        return new ReconciliationResource(type, key, "Queue " + key, ownership, status, expectedName, ip == null ? null : ip + "/32", observed.name(), observed.target(), conflict, List.of(new ReconciliationFinding(code, description, severity)));
    }
    private void addQueueSemanticDrift(RouterSimpleQueue queue, List<ReconciliationFinding> drift) {
        if (queue.maxLimit() != null && !ManagedSimpleQueueSemantics.isSafeManagedQueue(queue)) {
            drift.add(new ReconciliationFinding("MANAGED_QUEUE_DRIFT",
                    "Campos de matching, CIR, burst, total, scheduling ou propriedade desconhecida estão fora do estado exato gerenciado.",
                    PlanSeverity.BLOCKING));
        }
    }

    private ReconciliationSummary summarize(List<ReconciliationResource> resources) {
        return new ReconciliationSummary(
                (int) resources.stream().filter(resource -> resource.ownership() == ResourceOwnership.MANAGED).count(),
                (int) resources.stream().filter(resource -> resource.ownership() == ResourceOwnership.FOREIGN).count(),
                (int) resources.stream().filter(resource -> resource.status() == ReconciliationStatus.IN_SYNC).count(),
                (int) resources.stream().filter(resource -> resource.status() == ReconciliationStatus.DRIFTED).count(),
                (int) resources.stream().filter(resource -> resource.status() == ReconciliationStatus.MISSING).count(),
                (int) resources.stream().filter(resource -> resource.status() == ReconciliationStatus.CONFLICT).count(),
                (int) resources.stream().filter(resource -> resource.status() == ReconciliationStatus.AMBIGUOUS_OWNERSHIP).count(),
                (int) resources.stream().filter(resource -> resource.status() == ReconciliationStatus.NOT_APPLICABLE).count());
    }

    /** Resource naming convention only; ownership remains the exact comment. */
    public static String expectedQueueName(String interfaceName) {
        return ManagedResourceIdentifier.expectedPortQueueName(interfaceName);
    }

    /**
     * Detects whether a foreign queue competes with the local future queue.
     * Overlap never implies ownership: a foreign queue whose target overlaps a
     * managed network is a FOREIGN conflict, never a MANAGED resource. A
     * target that is not a single CIDR is only treated as related when there is
     * a conservative signal (a blank/all-target target, the local interface,
     * the local network, or a parseable CIDR token in a combination). An
     * unrelated unsupported target remains unadopted and does not create a
     * false conflict.
     */
    private static boolean foreignQueueConflicts(ManagedPort port, String expectedName, RouterSimpleQueue queue) {
        if (expectedName.equals(queue.name())) {
            return true;
        }
        if (targetOverlaps(port.network(), queue.target())) {
            return true;
        }
        return unsupportedTargetMayAffectPort(port, queue.target());
    }

    private static boolean isExactManagedDeviceQueue(RouterSimpleQueue queue) {
        String comment = queue.comment();
        if (comment == null || !comment.startsWith("MTMGR:DEVICE:")) return false;
        String mac = comment.substring("MTMGR:DEVICE:".length()).replace('-', ':');
        return ManagedResourceIdentifier.isOwnedByDevice(comment, mac);
    }

    private static boolean targetOverlaps(String localNetwork, String observedTarget) {
        if (localNetwork == null || localNetwork.isBlank() || observedTarget == null || observedTarget.isBlank()) {
            return false;
        }
        CidrRange localRange = CidrValidator.parseRange(localNetwork);
        if (localRange == null) {
            return false;
        }
        CidrRange observedRange = CidrValidator.parseRange(observedTarget);
        if (observedRange != null && localRange.overlaps(observedRange)) {
            return true;
        }
        // RouterOS can represent a target as a combination. Do not attempt to
        // understand the full grammar; safely inspect only comma/whitespace
        // separated literal CIDR/IP tokens and ignore the rest.
        return Arrays.stream(observedTarget.split("[,\\s]+"))
                .map(CidrValidator::parseRange)
                .filter(Objects::nonNull)
                .anyMatch(localRange::overlaps);
    }

    private static boolean unsupportedTargetMayAffectPort(ManagedPort port, String observedTarget) {
        if (observedTarget == null || observedTarget.isBlank()) {
            // A blank Simple Queue target is broad rather than evidence of no
            // relation; block future adoption conservatively.
            return true;
        }
        String localInterface = port.interfaceName();
        String localNetwork = port.network();
        return Arrays.stream(observedTarget.split("[,\\s]+"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .anyMatch(token -> token.equals(localInterface) || token.equals(localNetwork));
    }
}
