package com.mikrotikmanager.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Observational assessment of whether the installation has enough information
 * for a later, separately designed write phase.
 *
 * <p>The report is not an execution authorization. Phase 3 structurally keeps
 * {@code executionEnabled} false even if every preflight fact is healthy and
 * even if {@code mikrotik.write-enabled} is true.</p>
 */
public record WriteReadinessReport(
        Instant generatedAt,
        boolean mockMode,
        boolean writeFlagEnabled,
        boolean readyForFutureExecution,
        boolean executionEnabled,
        String phaseNotice,
        List<ReadinessCheck> checks,
        ReadinessSummary summary
) {
    public static final String PHASE_3_EXECUTION_DISABLED_NOTICE =
            "A infraestrutura pode estar preparada para futura habilitação de escrita. "
                    + "A execução RouterOS permanece desabilitada na Fase 3.";

    public WriteReadinessReport {
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(phaseNotice, "phaseNotice");
        checks = List.copyOf(checks);
        Objects.requireNonNull(summary, "summary");
        if (executionEnabled) {
            throw new IllegalArgumentException("Phase 3 write readiness must never enable RouterOS execution.");
        }
        if (readyForFutureExecution && checks.stream().anyMatch(check -> check.severity() == ReadinessSeverity.BLOCKING
                && !check.satisfied())) {
            throw new IllegalArgumentException("A readiness report with blocking findings cannot be ready for future execution.");
        }
    }

    public boolean hasBlockingFindings() {
        return checks.stream().anyMatch(check -> check.severity() == ReadinessSeverity.BLOCKING
                && !check.satisfied());
    }
}
