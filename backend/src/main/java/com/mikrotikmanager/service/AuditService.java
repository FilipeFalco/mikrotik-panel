package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.AuditLog;
import com.mikrotikmanager.persistence.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditService {
    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void record(String action, String targetType, String targetIdentifier, String previousValue,
                       String newValue, boolean success, String errorMessage) {
        auditLogRepository.insert(action, targetType, targetIdentifier, previousValue, newValue, success, errorMessage);
    }

    public List<AuditLog> recent(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        return auditLogRepository.findRecent(limit);
    }
}
