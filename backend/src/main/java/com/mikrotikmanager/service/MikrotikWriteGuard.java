package com.mikrotikmanager.service;

import com.mikrotikmanager.config.MikrotikProperties;
import com.mikrotikmanager.support.ApiErrorCode;
import com.mikrotikmanager.support.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Central safety switch for RouterOS mutations only. Local SQLite state is not
 * guarded; mock mode remains writable so local development can exercise the UI
 * without a physical router.
 */
@Component
public class MikrotikWriteGuard {
    private final MikrotikProperties properties;

    public MikrotikWriteGuard(MikrotikProperties properties) {
        this.properties = properties;
    }

    /**
     * Must be called immediately before a gateway operation that mutates RouterOS.
     */
    public void checkRouterWriteAllowed() {
        if (properties.mockMode() || properties.writeEnabled()) {
            return;
        }
        throw new ApiException(ApiErrorCode.MIKROTIK_WRITES_DISABLED, HttpStatus.FORBIDDEN,
                "As operações de escrita no MikroTik estão desabilitadas para esta instância.");
    }
}
