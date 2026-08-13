package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.ManagedDeviceBlockRule;

/** Narrow mutation boundary for the two Phase 4 device block operations. */
public interface DeviceBlockMutationGateway {
    void createManagedDeviceBlockRule(ManagedDeviceBlockRule rule, String placeBeforeId);

    void deleteManagedDeviceBlockRule(String routerOsId);
}
