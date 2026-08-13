package com.mikrotikmanager.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriteReadinessReportTest {
    @Test
    void preservesThePreviewBoundaryWhileAllowingThePhaseFourCapabilityFlag() {
        WriteReadinessReport report = report(true, false, List.of(check("ROUTEROS_CONNECTED", true, ReadinessSeverity.INFO)));

        assertThat(report.readyForFutureExecution()).isTrue();
        assertThat(report.executionEnabled()).isFalse();
        assertThat(report.phaseNotice()).contains("execução RouterOS permanece desabilitada na Fase 3");
    }

    @Test
    void permitsThePhaseFourExecutionCapabilityToBeReported() {
        WriteReadinessReport report = new WriteReadinessReport(
                Instant.now(), false, false, true, true,
                WriteReadinessReport.PHASE_3_EXECUTION_DISABLED_NOTICE,
                List.of(check("ROUTEROS_CONNECTED", true, ReadinessSeverity.INFO)), summary());

        assertThat(report.executionEnabled()).isTrue();
    }

    @Test
    void blockingUnsatisfiedCheckCannotBeReportedAsFutureReady() {
        assertThatIllegalArgumentException().isThrownBy(() -> report(true, false,
                List.of(check("FOREIGN_CONFLICT", false, ReadinessSeverity.BLOCKING))))
                .withMessageContaining("blocking findings");
    }

    @Test
    void makesCheckListImmutable() {
        List<ReadinessCheck> checks = new ArrayList<>();
        checks.add(check("ROUTEROS_CONNECTED", true, ReadinessSeverity.INFO));

        WriteReadinessReport report = report(true, false, checks);
        checks.clear();

        assertThat(report.checks()).hasSize(1);
        assertThatThrownBy(() -> report.checks().add(check("DHCP_AVAILABLE", true, ReadinessSeverity.INFO)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private WriteReadinessReport report(boolean futureReady, boolean executionEnabled, List<ReadinessCheck> checks) {
        return new WriteReadinessReport(Instant.now(), false, false, futureReady, executionEnabled,
                WriteReadinessReport.PHASE_3_EXECUTION_DISABLED_NOTICE, checks, summary());
    }

    private ReadinessCheck check(String code, boolean satisfied, ReadinessSeverity severity) {
        return new ReadinessCheck(code, code, satisfied, severity, "safe summary");
    }

    private ReadinessSummary summary() {
        return new ReadinessSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
