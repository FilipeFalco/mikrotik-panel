package com.mikrotikmanager.gateway;

import com.mikrotikmanager.domain.ManagedDeviceBlockRule;

/** Adapter preserving mock-mode simulations without RouterOS credentials. */
public final class MockDeviceBlockMutationGateway implements DeviceBlockMutationGateway {
    private final MikrotikGateway gateway;

    public MockDeviceBlockMutationGateway(MikrotikGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void createManagedDeviceBlockRule(ManagedDeviceBlockRule rule, String placeBeforeId) {
        gateway.blockDevice(rule.macAddress());
    }

    @Override
    public void deleteManagedDeviceBlockRule(String routerOsId) {
        if (routerOsId == null || !routerOsId.startsWith("mock-block-")) {
            throw new IllegalArgumentException("Mock managed block identifier is invalid.");
        }
        gateway.unblockDevice(routerOsId.substring("mock-block-".length()).replace('-', ':'));
    }
}
