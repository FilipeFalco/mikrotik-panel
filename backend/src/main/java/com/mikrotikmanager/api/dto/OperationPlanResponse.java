package com.mikrotikmanager.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Ephemeral dry-run response. A plan id and fingerprint are diagnostic
 * references only; neither authorizes a future RouterOS write.
 */
public record OperationPlanResponse(
        String planId,
        String operationType,
        PlanTargetResponse target,
        PlanStateResponse currentState,
        PlanStateResponse desiredState,
        String ownership,
        List<PlanPreconditionResponse> preconditions,
        List<PlanWarningResponse> warnings,
        List<PlanConflictResponse> conflicts,
        List<PlanChangeResponse> plannedChanges,
        boolean changeRequired,
        boolean readyForFutureExecution,
        boolean executable,
        String executionDisabledReason,
        Instant generatedAt,
        String snapshotFingerprint
) {
    public OperationPlanResponse {
        preconditions = List.copyOf(preconditions);
        warnings = List.copyOf(warnings);
        conflicts = List.copyOf(conflicts);
        plannedChanges = List.copyOf(plannedChanges);
    }
}
