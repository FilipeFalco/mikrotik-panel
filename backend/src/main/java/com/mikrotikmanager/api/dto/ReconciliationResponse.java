package com.mikrotikmanager.api.dto;

import java.time.Instant;
import java.util.List;

/** On-demand, observational reconciliation response. */
public record ReconciliationResponse(
        Instant generatedAt,
        String snapshotFingerprint,
        int observedInterfaceCount,
        int observedDhcpServerCount,
        int observedLeaseCount,
        int observedSimpleQueueCount,
        boolean fastTrackDetected,
        List<ReconciliationResourceResponse> resources,
        ReconciliationSummaryResponse summary
) {
    public ReconciliationResponse {
        resources = List.copyOf(resources);
    }
}
