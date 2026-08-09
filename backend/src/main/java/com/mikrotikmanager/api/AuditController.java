package com.mikrotikmanager.api;

import com.mikrotikmanager.api.dto.AuditLogResponse;
import com.mikrotikmanager.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public List<AuditLogResponse> list(@RequestParam(defaultValue = "100") int limit) {
        return auditService.recent(limit).stream().map(ResponseMapper::audit).toList();
    }
}
