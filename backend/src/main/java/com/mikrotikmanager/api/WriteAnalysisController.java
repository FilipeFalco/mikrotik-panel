package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.WriteAnalysisResponse;
import com.mikrotikmanager.api.dto.WriteReadinessResponse;
import com.mikrotikmanager.api.dto.ReconciliationResponse;
import com.mikrotikmanager.service.WriteAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Combined write-analysis endpoint. Readiness and reconciliation are derived
 * from the same RouterOS snapshot in a single observation, so the UI shows one
 * coherent moment instead of two separate snapshots. It is observational and
 * on-demand; it never mutates RouterOS.
 */
@RestController
@RequestMapping("/api/write-analysis")
public class WriteAnalysisController {
    private final WriteAnalysisService writeAnalysisService;

    public WriteAnalysisController(WriteAnalysisService writeAnalysisService) {
        this.writeAnalysisService = writeAnalysisService;
    }

    @GetMapping
    public WriteAnalysisResponse analyze() {
        WriteAnalysisService.WriteAnalysis analysis = writeAnalysisService.analyze();
        WriteReadinessResponse readiness = Phase3ResponseMapper.writeReadiness(analysis.readiness());
        ReconciliationResponse reconciliation = analysis.reconciliation() == null
                ? null
                : Phase3ResponseMapper.reconciliation(analysis.reconciliation());
        return new WriteAnalysisResponse(analysis.snapshotFingerprint(), readiness, reconciliation);
    }
}
