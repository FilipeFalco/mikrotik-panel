package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.WriteReadinessResponse;
import com.mikrotikmanager.service.WriteReadinessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local future-write readiness diagnostic; it cannot enable RouterOS writes. */
@RestController
@RequestMapping("/api/write-readiness")
public class WriteReadinessController {
    private final WriteReadinessService writeReadinessService;

    public WriteReadinessController(WriteReadinessService writeReadinessService) {
        this.writeReadinessService = writeReadinessService;
    }

    @GetMapping
    public WriteReadinessResponse analyze() {
        return Phase3ResponseMapper.writeReadiness(writeReadinessService.analyze());
    }
}
