package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.ReconciliationResponse;
import com.mikrotikmanager.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local, on-demand reconciliation API. It only observes RouterOS state. */
@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {
    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public ReconciliationResponse analyze() {
        return Phase3ResponseMapper.reconciliation(reconciliationService.analyze());
    }
}
