package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedPort;
import com.mikrotikmanager.domain.ManagedPortRole;
import com.mikrotikmanager.domain.PlanSeverity;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.ReconciliationResource;
import com.mikrotikmanager.domain.ReconciliationStatus;
import com.mikrotikmanager.domain.ResourceOwnership;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final String INTERFACE = "ether2";
    private static final String NETWORK = "10.10.10.0/24";

    @Test
    void classifiesAManualQueueWithTheExpectedTargetAsForeignConflictWithoutAdoptingIt() {
        ReconciliationReport report = analyze(List.of(queue("cliente-joao", "Criada manualmente", NETWORK, knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.CONFLICT);
        assertThat(resource.conflict()).isTrue();
        assertThat(resource.observedName()).isEqualTo("cliente-joao");
        assertThat(resource.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("FOREIGN_QUEUE_CONFLICT");
            assertThat(finding.severity()).isEqualTo(PlanSeverity.BLOCKING);
        });
    }

    @Test
    void reportsAnExactOwnedQueueWithExpectedNameAndTargetAsInSync() {
        ReconciliationReport report = analyze(List.of(queue(
                ManagedResourceIdentifier.expectedPortQueueName(INTERFACE),
                ManagedResourceIdentifier.expectedPortComment(INTERFACE), NETWORK, knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.MANAGED);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.IN_SYNC);
        assertThat(resource.conflict()).isFalse();
        assertThat(resource.findings()).isEmpty();
        assertThat(report.summary().inSync()).isEqualTo(1);
        assertThat(report.summary().managed()).isEqualTo(1);
    }

    @Test
    void reportsOwnedQueueTargetDriftWithoutTryingToCorrectIt() {
        ReconciliationReport report = analyze(List.of(queue(
                ManagedResourceIdentifier.expectedPortQueueName(INTERFACE),
                ManagedResourceIdentifier.expectedPortComment(INTERFACE), "10.99.0.0/24", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.MANAGED);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.DRIFTED);
        assertThat(resource.conflict()).isFalse();
        assertThat(resource.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("QUEUE_TARGET_DRIFT");
            assertThat(finding.severity()).isEqualTo(PlanSeverity.BLOCKING);
        });
        assertThat(report.summary().drifted()).isEqualTo(1);
    }

    @Test
    void reportsExactOwnedPortQueueWithWrongNameAsBlockingDrift() {
        ReconciliationReport report = analyze(List.of(queue(
                "manual-renamed", ManagedResourceIdentifier.expectedPortComment(INTERFACE), NETWORK, knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.MANAGED);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.DRIFTED);
        assertThat(resource.conflict()).isFalse();
        assertThat(resource.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("QUEUE_NAME_DRIFT");
            assertThat(finding.severity()).isEqualTo(PlanSeverity.BLOCKING);
        });
    }

    @Test
    void exactOwnershipRemainsManagedWhenItsOverlappingTargetIsDrifted() {
        ReconciliationReport report = analyze(List.of(queue(
                ManagedResourceIdentifier.expectedPortQueueName(INTERFACE),
                ManagedResourceIdentifier.expectedPortComment(INTERFACE), "10.10.10.0/25", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.MANAGED);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.DRIFTED);
        assertThat(resource.conflict()).isFalse();
    }

    @Test
    void treatsAnOwnedQueueWithUnreadableLimitAsDriftRatherThanInventingALimit() {
        ReconciliationReport report = analyze(List.of(queue(
                ManagedResourceIdentifier.expectedPortQueueName(INTERFACE),
                ManagedResourceIdentifier.expectedPortComment(INTERFACE), NETWORK, null)));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.MANAGED);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.DRIFTED);
        assertThat(resource.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("QUEUE_LIMIT_UNREADABLE");
            assertThat(finding.severity()).isEqualTo(PlanSeverity.BLOCKING);
        });
    }

    @Test
    void reportsMissingWhenNoOwnedQueueExistsAndIgnoresUnrelatedManualQueues() {
        ReconciliationReport report = analyze(List.of(queue("manual-other-network", "manual", "10.50.0.0/24", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.UNKNOWN);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.MISSING);
        assertThat(resource.conflict()).isFalse();
        assertThat(report.summary().missing()).isEqualTo(1);
    }

    @Test
    void blocksDuplicateExactOwnedQueuesInsteadOfSelectingTheFirstOne() {
        String ownership = ManagedResourceIdentifier.expectedPortComment(INTERFACE);
        ReconciliationReport report = analyze(List.of(
                queue("mtmgr-port-ether2-a", ownership, NETWORK, knownLimit()),
                queue("mtmgr-port-ether2-b", ownership, NETWORK, knownLimit())
        ));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.MANAGED);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.AMBIGUOUS_OWNERSHIP);
        assertThat(resource.conflict()).isTrue();
        assertThat(resource.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("DUPLICATE_MANAGED_QUEUE");
            assertThat(finding.severity()).isEqualTo(PlanSeverity.BLOCKING);
        });
        assertThat(report.summary().ambiguous()).isEqualTo(1);
    }

    @Test
    void treatsFutureNameCollisionWithManualCommentAsForeignConflict() {
        ReconciliationReport report = analyze(List.of(queue(
                ManagedResourceIdentifier.expectedPortQueueName(INTERFACE), "manual", "10.50.0.0/24", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.CONFLICT);
        assertThat(resource.conflict()).isTrue();
        assertThat(resource.observedName()).isEqualTo(ManagedResourceIdentifier.expectedPortQueueName(INTERFACE));
    }

    @Test
    void foreignQueueWithOverlappingSubnetTargetIsConflictWithoutAdoptingIt() {
        ReconciliationReport report = analyze(List.of(queue("cliente-joao", "manual", "10.10.10.0/25", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.CONFLICT);
        assertThat(resource.conflict()).isTrue();
        assertThat(resource.observedTarget()).isEqualTo("10.10.10.0/25");
        assertThat(resource.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("FOREIGN_QUEUE_CONFLICT");
            assertThat(finding.severity()).isEqualTo(PlanSeverity.BLOCKING);
        });
    }

    @Test
    void foreignQueueWithOverlappingUpperSubnetTargetIsConflict() {
        ReconciliationReport report = analyze(List.of(queue("cliente-joao", "manual", "10.10.10.128/25", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.CONFLICT);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(resource.observedTarget()).isEqualTo("10.10.10.128/25");
    }

    @Test
    void foreignQueueWithOverlappingSupernetTargetIsConflict() {
        ReconciliationReport report = analyze(List.of(queue("cliente-joao", "manual", "10.10.0.0/16", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.CONFLICT);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(resource.observedTarget()).isEqualTo("10.10.0.0/16");
    }

    @Test
    void foreignQueueWithNonOverlappingTargetDoesNotConflict() {
        ReconciliationReport report = analyze(List.of(queue("cliente-joao", "manual", "10.10.11.0/24", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.MISSING);
        assertThat(resource.conflict()).isFalse();
        assertThat(report.summary().conflicts()).isZero();
        assertThat(report.summary().missing()).isEqualTo(1);
    }

    @Test
    void foreignQueueWithCompletelySeparateNetworkDoesNotConflict() {
        ReconciliationReport report = analyze(List.of(queue("cliente-joao", "manual", "10.20.0.0/16", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.MISSING);
        assertThat(resource.conflict()).isFalse();
        assertThat(report.summary().conflicts()).isZero();
    }

    @Test
    void foreignQueueWithInterfaceTargetIsConservativelyAConflict() {
        ReconciliationReport report = analyze(List.of(queue("cliente-joao", "manual", INTERFACE, knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.CONFLICT);
        assertThat(resource.conflict()).isTrue();
    }

    @Test
    void overlapConflictNeverImpliesManagedOwnershipWhichRemainsExactCommentOnly() {
        ReconciliationReport report = analyze(List.of(queue("cliente-joao", "manual", "10.10.10.0/24", knownLimit())));

        ReconciliationResource resource = resource(report);
        assertThat(resource.ownership()).isEqualTo(ResourceOwnership.FOREIGN);
        assertThat(resource.status()).isEqualTo(ReconciliationStatus.CONFLICT);
    }

    private ReconciliationReport analyze(List<RouterSimpleQueue> queues) {
        RouterSnapshotService snapshots = mock(RouterSnapshotService.class);
        ManagedPortRepository ports = mock(ManagedPortRepository.class);
        when(ports.findAll()).thenReturn(List.of(clientPort()));
        ReconciliationService service = new ReconciliationService(snapshots, ports, Clock.fixed(NOW, ZoneOffset.UTC));

        ReconciliationReport report = service.analyze(snapshot(queues));

        verify(ports).findAll();
        verifyNoInteractions(snapshots);
        return report;
    }

    private RouterSnapshot snapshot(List<RouterSimpleQueue> queues) {
        return new RouterSnapshot(NOW,
                List.of(new RouterInterface(INTERFACE, "ether", true, false, null)),
                List.of(), List.of(), List.of(), queues, List.of(), List.of());
    }

    private ManagedPort clientPort() {
        return new ManagedPort(1L, INTERFACE, "Clientes", "metadata local", NETWORK, "dhcp-clientes",
                ManagedPortRole.CLIENT, true, NOW, NOW);
    }

    private RouterSimpleQueue queue(String name, String comment, String target, SpeedLimit limit) {
        return new RouterSimpleQueue("*1", name, comment, target, limit, false, false);
    }

    private SpeedLimit knownLimit() {
        return new SpeedLimit(100_000_000L, 20_000_000L);
    }

    private ReconciliationResource resource(ReconciliationReport report) {
        return report.resources().getFirst();
    }
}
