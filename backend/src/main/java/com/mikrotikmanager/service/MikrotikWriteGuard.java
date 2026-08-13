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

    /** Fase 4's second, domain-specific write gate. */
    public void checkDeviceBlockWriteAllowed() {
        if (properties.mockMode()) {
            return;
        }
        if (!properties.writeEnabled()) {
            throw new ApiException(ApiErrorCode.MIKROTIK_WRITES_DISABLED, HttpStatus.FORBIDDEN,
                    "As operações de escrita no MikroTik estão desabilitadas para esta instância.");
        }
        if (!properties.deviceBlockWritesEnabled()) {
            throw new ApiException(ApiErrorCode.DEVICE_BLOCK_WRITES_DISABLED, HttpStatus.FORBIDDEN,
                    "A escrita de bloqueio de dispositivos está desabilitada para esta instância.");
        }
        if (!properties.writeCredentialsConfigured()) {
            throw new ApiException(ApiErrorCode.MIKROTIK_WRITE_CREDENTIALS_MISSING, HttpStatus.FORBIDDEN,
                    "As credenciais separadas de escrita do MikroTik não estão configuradas.");
        }
    }
}
