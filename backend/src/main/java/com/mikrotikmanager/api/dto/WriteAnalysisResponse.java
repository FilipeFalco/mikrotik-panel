package com.mikrotikmanager.api.dto;

/**
 * Combined write-analysis response. Readiness and reconciliation are derived
 * from the same RouterOS snapshot, so the two views always refer to one
 * observed moment. It never signals RouterOS execution enabled.
 */
public record WriteAnalysisResponse(
        String snapshotFingerprint,
        WriteReadinessResponse readiness,
        ReconciliationResponse reconciliation
) {
}
