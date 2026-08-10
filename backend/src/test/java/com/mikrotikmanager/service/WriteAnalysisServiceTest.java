package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.GatewayConnectionStatus;
import com.mikrotikmanager.domain.ReadinessSummary;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.ReconciliationSummary;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.WriteReadinessReport;
import com.mikrotikmanager.gateway.MikrotikGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WriteAnalysisServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");

    @Test
    void derivesReadinessAndReconciliationFromTheSameCapturedSnapshot() {
        MikrotikGateway gateway = mock(MikrotikGateway.class);
        RouterSnapshotService snapshots = mock(RouterSnapshotService.class);
        ReconciliationService reconciliation = mock(ReconciliationService.class);
        WriteReadinessService readiness = mock(WriteReadinessService.class);

        GatewayConnectionStatus connection = new GatewayConnectionStatus(true, false, "router", 443,
                "7.20", 1L, "safe", true, false);
        RouterSnapshot snapshot = new RouterSnapshot(NOW, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        ReconciliationReport reconciliationReport = new ReconciliationReport(NOW, snapshot.fingerprint(),
                0, 0, 0, 0, false, List.of(), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0));
        WriteReadinessReport readinessReport = new WriteReadinessReport(NOW, false, false, true, false,
                WriteReadinessReport.PHASE_3_EXECUTION_DISABLED_NOTICE, List.of(),
                new ReadinessSummary(0, 0, 0, 0, 0, 0, 0, 0, 0));
        when(gateway.connectionStatus()).thenReturn(connection);
        when(snapshots.capture()).thenReturn(snapshot);
        when(reconciliation.analyze(snapshot)).thenReturn(reconciliationReport);
        when(readiness.analyze(connection, reconciliationReport)).thenReturn(readinessReport);

        WriteAnalysisService.WriteAnalysis result = new WriteAnalysisService(gateway, snapshots, reconciliation, readiness)
                .analyze();

        assertThat(result.readiness()).isSameAs(readinessReport);
        assertThat(result.reconciliation()).isSameAs(reconciliationReport);
        assertThat(result.snapshotFingerprint()).isEqualTo(snapshot.fingerprint());
        verify(gateway).connectionStatus();
        verify(snapshots).capture();
        verify(reconciliation).analyze(snapshot);
        verify(readiness).analyze(connection, reconciliationReport);
        verifyNoMoreInteractions(gateway, snapshots, reconciliation, readiness);
    }
}
