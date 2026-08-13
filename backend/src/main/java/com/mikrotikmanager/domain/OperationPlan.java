package com.mikrotikmanager.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ephemeral snapshot of a requested future RouterOS operation.
 *
 * <p>It is diagnostic evidence only. {@code executable} is structurally
 * required to stay {@code false} for Phase 4 previews; a plan id is never an
 * authorization token and a future executor must rebuild this assessment.
 * </p>
 */
public record OperationPlan(
        UUID operationId,
        PlannedOperationType operationType,
        PlanTarget target,
        PlanState currentState,
        PlanState desiredState,
        ResourceOwnership ownership,
        List<PlanPrecondition> preconditions,
        List<PlanWarning> warnings,
        List<PlanConflict> conflicts,
        List<PlanChange> plannedChanges,
        boolean changeRequired,
        boolean readyForFutureExecution,
        boolean executable,
        String executionDisabledReason,
        Instant generatedAt,
        String snapshotFingerprint
) {
    public OperationPlan {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(desiredState, "desiredState");
        Objects.requireNonNull(ownership, "ownership");
        preconditions = List.copyOf(preconditions);
        warnings = List.copyOf(warnings);
        conflicts = List.copyOf(conflicts);
        plannedChanges = List.copyOf(plannedChanges);
        Objects.requireNonNull(executionDisabledReason, "executionDisabledReason");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(snapshotFingerprint, "snapshotFingerprint");
        if (executable) {
            throw new IllegalArgumentException("Phase 4 operation plans must never be executable; they remain preview-only.");
        }
    }

    public boolean isNoOpCandidate() {
        return !changeRequired && readyForFutureExecution;
    }
}
