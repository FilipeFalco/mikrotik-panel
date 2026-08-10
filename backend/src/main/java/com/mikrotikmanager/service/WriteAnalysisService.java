package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.WriteReadinessReport;
import com.mikrotikmanager.gateway.MikrotikGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Produces a combined write-analysis from a single RouterOS snapshot.
 *
 * <p>The combined flow captures exactly one snapshot, derives reconciliation
 * from it, and then derives readiness from the same reconciliation — instead
 * of the legacy flow where {@code /api/write-readiness} and
 * {@code /api/reconciliation} each captured their own snapshot. This guarantees
 * that the readiness and reconciliation views shown together on the UI refer to
 * the same observed moment and avoids duplicating the six RouterOS collection
 * reads. The single allowed extra read is {@link MikrotikGateway#connectionStatus()},
 * used only to decide whether a snapshot can be attempted.</p>
 *
 * <p>The service is strictly observational: it never requests a mutation, never
 * enables RouterOS execution, and never persists anything.</p>
 */
@Service
public class WriteAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(WriteAnalysisService.class);

    private final MikrotikGateway gateway;
    private final RouterSnapshotService snapshotService;
    private final ReconciliationService reconciliationService;
    private final WriteReadinessService writeReadinessService;

    @Autowired
    public WriteAnalysisService(MikrotikGateway gateway, RouterSnapshotService snapshotService,
                                ReconciliationService reconciliationService, WriteReadinessService writeReadinessService) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService");
        this.writeReadinessService = Objects.requireNonNull(writeReadinessService, "writeReadinessService");
    }

    /**
     * Holds the combined readiness and reconciliation views, both derived from
     * the same snapshot. When RouterOS is unavailable or the snapshot cannot be
     * captured, readiness carries the structured unavailable/analysis-unavailable
     * report and reconciliation is {@code null}; the snapshot fingerprint is
     * {@code null} in that case.
     */
    public record WriteAnalysis(WriteReadinessReport readiness, ReconciliationReport reconciliation, String snapshotFingerprint) {
    }

    public WriteAnalysis analyze() {
        GatewayConnectionStatus connection = connectionStatus();
        if (connection == null || !connection.connected()) {
            WriteReadinessReport readiness = writeReadinessService.analyze(
                    connection == null ? unavailableConnection() : connection, null);
            log.info("Write analysis skipped: RouterOS not connected.");
            return new WriteAnalysis(readiness, null, null);
        }

        try {
            RouterSnapshot snapshot = snapshotService.capture();
            ReconciliationReport reconciliation = reconciliationService.analyze(snapshot);
            WriteReadinessReport readiness = writeReadinessService.analyze(connection, reconciliation);
            log.info("Write analysis completed from a single snapshot fingerprint={} readyForFutureExecution={}",
                    reconciliation.snapshotFingerprint(), readiness.readyForFutureExecution());
            return new WriteAnalysis(readiness, reconciliation, reconciliation.snapshotFingerprint());
        } catch (RuntimeException exception) {
            // Do not log or expose transport exception text; it may contain
            // sensitive request context as an implementation detail.
            log.warn("Write analysis could not obtain a read-only RouterOS snapshot.");
            WriteReadinessReport readiness = writeReadinessService.analyze(connection, null);
            return new WriteAnalysis(readiness, null, null);
        }
    }

    private GatewayConnectionStatus connectionStatus() {
        try {
            return gateway.connectionStatus();
        } catch (RuntimeException exception) {
            log.warn("Write analysis could not determine RouterOS connection status.");
            return null;
        }
    }

    private GatewayConnectionStatus unavailableConnection() {
        return new GatewayConnectionStatus(false, false, null, 0, null, 0L, "RouterOS indisponível.", false, true);
    }
}
