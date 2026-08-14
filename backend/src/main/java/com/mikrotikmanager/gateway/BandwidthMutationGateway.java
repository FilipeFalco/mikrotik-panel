package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.ManagedSimpleQueue;

/** Narrow Fase 5 write boundary; callers cannot submit a raw RouterOS request. */
public interface BandwidthMutationGateway {
    void createManagedQueue(ManagedSimpleQueue queue, String placeBeforeId);
    void updateManagedQueue(String freshRouterOsId, ManagedSimpleQueue queue);
    void deleteManagedQueue(String freshRouterOsId);
}
