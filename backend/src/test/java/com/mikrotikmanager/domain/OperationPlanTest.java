package com.mikrotikmanager.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationPlanTest {

    @Test
    void refusesToRepresentAnExecutablePlanInPhaseThree() {
        assertThatThrownBy(() -> plan(true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never be executable");
    }

    @Test
    void marksAPlanWithoutARequiredChangeAsNoOpCandidate() {
        assertThat(plan(false, false, true).isNoOpCandidate()).isTrue();
    }

    private OperationPlan plan(boolean executable, boolean changeRequired) {
        return plan(executable, changeRequired, false);
    }

    private OperationPlan plan(boolean executable, boolean changeRequired, boolean readyForFutureExecution) {
        return new OperationPlan(
                UUID.randomUUID(),
                PlannedOperationType.BLOCK_DEVICE,
                new PlanTarget("AA:BB:CC:DD:EE:01", "Device", "AA:BB:CC:DD:EE:01", "ether2"),
                PlanState.unavailable(),
                PlanState.unavailable(),
                ResourceOwnership.UNKNOWN,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                changeRequired,
                readyForFutureExecution,
                executable,
                "Phase 3 is dry-run only.",
                Instant.now(),
                "snapshot"
        );
    }
}
