package com.mikrotikmanager.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Observational assessment of whether the installation has enough information
 * for a later, separately designed write phase.
 *
 * <p>The report is diagnostic capability information, never an execution
 * authorization. The executor always rebuilds and revalidates its own state.</p>
 */
public record WriteReadinessReport(
        Instant generatedAt,
        boolean mockMode,
        boolean writeFlagEnabled,
        boolean readyForFutureExecution,
        boolean executionEnabled,
        String phaseNotice,
        List<ReadinessCheck> checks,
        ReadinessSummary summary,
        boolean deviceBlockWriteFlagEnabled,
        boolean writeCredentialsConfigured,
        String blockingStrategy,
        boolean firewallOrderingAnalyzable
) {
    public static final String PHASE_3_EXECUTION_DISABLED_NOTICE =
            "A infraestrutura pode estar preparada para futura habilitação de escrita. "
                    + "A execução RouterOS permanece desabilitada na Fase 3.";

    public static final String PHASE_4_DEVICE_BLOCK_NOTICE =
            "A execução real está limitada a regras MTMGR FIREWALL_MAC_RULE de bloqueio/liberação; "
                    + "o estado será revalidado imediatamente antes da escrita.";

    /** Compatibility constructor for Phase 3 tests and stored diagnostic fixtures. */
    public WriteReadinessReport(Instant generatedAt, boolean mockMode, boolean writeFlagEnabled,
                                boolean readyForFutureExecution, boolean executionEnabled, String phaseNotice,
                                List<ReadinessCheck> checks, ReadinessSummary summary) {
        this(generatedAt, mockMode, writeFlagEnabled, readyForFutureExecution, executionEnabled, phaseNotice,
                checks, summary, false, false, "UNDECIDED", false);
    }

    public WriteReadinessReport {
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(phaseNotice, "phaseNotice");
        checks = List.copyOf(checks);
        Objects.requireNonNull(summary, "summary");
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
