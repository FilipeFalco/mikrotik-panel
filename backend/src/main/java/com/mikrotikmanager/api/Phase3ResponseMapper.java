package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.OperationPlanResponse;
import com.mikrotikmanager.api.dto.PlanChangeResponse;
import com.mikrotikmanager.api.dto.PlanConflictResponse;
import com.mikrotikmanager.api.dto.PlanPreconditionResponse;
import com.mikrotikmanager.api.dto.PlanStateResponse;
import com.mikrotikmanager.api.dto.PlanTargetResponse;
import com.mikrotikmanager.api.dto.PlanWarningResponse;
import com.mikrotikmanager.api.dto.ReadinessCheckResponse;
import com.mikrotikmanager.api.dto.ReadinessSummaryResponse;
import com.mikrotikmanager.api.dto.ReconciliationFindingResponse;
import com.mikrotikmanager.api.dto.ReconciliationResourceResponse;
import com.mikrotikmanager.api.dto.ReconciliationResponse;
import com.mikrotikmanager.api.dto.ReconciliationSummaryResponse;
import com.mikrotikmanager.api.dto.SpeedLimitResponse;
import com.mikrotikmanager.api.dto.WriteReadinessResponse;
import com.mikrotikmanager.domain.OperationPlan;
import com.mikrotikmanager.domain.PlanState;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.WriteReadinessReport;

/** Maps Phase 3 domain diagnostics into explicit, transport-safe API DTOs. */
final class Phase3ResponseMapper {
    private Phase3ResponseMapper() {
    }

    static OperationPlanResponse plan(OperationPlan plan) {
        return new OperationPlanResponse(
                plan.operationId().toString(),
                plan.operationType().name(),
                new PlanTargetResponse(plan.target().identifier(), plan.target().displayName(),
                        plan.target().macAddress(), plan.target().interfaceName()),
                state(plan.currentState()),
                state(plan.desiredState()),
                plan.ownership().name(),
                plan.preconditions().stream().map(precondition -> new PlanPreconditionResponse(
                        precondition.code(), precondition.description(), precondition.satisfied(), precondition.severity().name())).toList(),
                plan.warnings().stream().map(warning -> new PlanWarningResponse(
                        warning.code(), warning.description(), warning.severity().name())).toList(),
                plan.conflicts().stream().map(conflict -> new PlanConflictResponse(conflict.code(), conflict.resourceType(),
                        conflict.resourceName(), conflict.resourceTarget(), conflict.ownership().name(), conflict.description(),
                        conflict.severity().name())).toList(),
                plan.plannedChanges().stream().map(change -> new PlanChangeResponse(
                        change.action(), change.resourceType(), change.description())).toList(),
                plan.changeRequired(), plan.readyForFutureExecution(), plan.executable(), plan.executionDisabledReason(),
                plan.generatedAt(), plan.snapshotFingerprint());
    }

    static ReconciliationResponse reconciliation(ReconciliationReport report) {
        return new ReconciliationResponse(
                report.generatedAt(), report.snapshotFingerprint(), report.observedInterfaceCount(), report.observedDhcpServerCount(),
                report.observedLeaseCount(), report.observedSimpleQueueCount(), report.fastTrackDetected(),
                report.resources().stream().map(resource -> new ReconciliationResourceResponse(
                        resource.resourceType(), resource.resourceKey(), resource.displayName(), resource.ownership().name(),
                        resource.status().name(), resource.expectedName(), resource.expectedTarget(), resource.observedName(),
                        resource.observedTarget(), resource.conflict(), resource.findings().stream().map(finding ->
                                new ReconciliationFindingResponse(finding.code(), finding.description(), finding.severity().name()))
                                .toList())).toList(),
                new ReconciliationSummaryResponse(report.summary().managed(), report.summary().foreign(), report.summary().inSync(),
                        report.summary().drifted(), report.summary().missing(), report.summary().conflicts(),
                        report.summary().ambiguous(), report.summary().notApplicable()));
    }

    static WriteReadinessResponse writeReadiness(WriteReadinessReport report) {
        return new WriteReadinessResponse(
                report.generatedAt(), report.mockMode(), report.writeFlagEnabled(), report.readyForFutureExecution(),
                report.executionEnabled(), report.phaseNotice(), report.checks().stream().map(check -> new ReadinessCheckResponse(
                        check.code(), check.description(), check.satisfied(), check.severity().name(), check.detail())).toList(),
                new ReadinessSummaryResponse(report.summary().managed(), report.summary().foreign(), report.summary().inSync(),
                        report.summary().drifted(), report.summary().missing(), report.summary().conflicts(),
                        report.summary().ambiguous(), report.summary().managedPorts(), report.summary().validManagedPorts()),
                report.deviceBlockWriteFlagEnabled(), report.writeCredentialsConfigured(), report.blockingStrategy(),
                report.firewallOrderingAnalyzable());
    }

    private static PlanStateResponse state(PlanState state) {
        return new PlanStateResponse(state.displayName(), state.macAddress(), state.ipAddress(), state.interfaceName(),
                state.network(), speedLimit(state.speedLimit()), state.blocked(),
                state.blockingStrategy() == null ? null : state.blockingStrategy().name());
    }

    private static SpeedLimitResponse speedLimit(SpeedLimit speedLimit) {
        return speedLimit == null ? null : new SpeedLimitResponse(speedLimit.downloadBps(), speedLimit.uploadBps());
    }
}
