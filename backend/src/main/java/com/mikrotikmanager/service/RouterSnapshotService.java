package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.gateway.MikrotikGateway;
import com.mikrotikmanager.gateway.RouterSnapshotReader;
import com.mikrotikmanager.support.ApiErrorCode;
import com.mikrotikmanager.support.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Coordinates one explicit, batched, read-only RouterOS observation. */
@Service
public class RouterSnapshotService {
    private final MikrotikGateway gateway;

    public RouterSnapshotService(MikrotikGateway gateway) {
        this.gateway = gateway;
    }

    public RouterSnapshot capture() {
        if (gateway instanceof RouterSnapshotReader reader) {
            return reader.captureSnapshot();
        }
        throw new ApiException(ApiErrorCode.ROUTEROS_SNAPSHOT_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                "O gateway atual não disponibiliza um snapshot RouterOS para análise.");
    }
}
