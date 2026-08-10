package com.mikrotikmanager.api.dto;

import java.util.List;

/** One relevant expected resource and its observed reconciliation status. */
public record ReconciliationResourceResponse(
        String resourceType,
        String resourceKey,
        String displayName,
        String ownership,
        String status,
        String expectedName,
        String expectedTarget,
        String observedName,
        String observedTarget,
        boolean conflict,
        List<ReconciliationFindingResponse> findings
) {
    public ReconciliationResourceResponse {
        findings = List.copyOf(findings);
    }
}
