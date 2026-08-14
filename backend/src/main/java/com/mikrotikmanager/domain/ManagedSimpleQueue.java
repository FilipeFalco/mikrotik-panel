package com.mikrotikmanager.domain;

import com.mikrotikmanager.gateway.ManagedResourceIdentifier;

/** Backend-built allow-listed desired state for one MTMGR Simple Queue. */
public record ManagedSimpleQueue(String name, String comment, String target, String parent, SpeedLimit maxLimit) {
    public ManagedSimpleQueue {
        if (name == null || comment == null || target == null || parent == null || maxLimit == null) {
            throw new IllegalArgumentException("Managed Simple Queue requires complete desired state.");
        }
    }
    public static ManagedSimpleQueue port(String interfaceName, String network, SpeedLimit limit) {
        return new ManagedSimpleQueue(ManagedResourceIdentifier.expectedPortQueueName(interfaceName),
                ManagedResourceIdentifier.expectedPortComment(interfaceName), network, "none", limit);
    }
    public static ManagedSimpleQueue device(String mac, String ip, String parent, SpeedLimit limit) {
        return new ManagedSimpleQueue(ManagedResourceIdentifier.expectedDeviceQueueName(mac),
                ManagedResourceIdentifier.expectedDeviceComment(mac), ip + "/32", parent == null ? "none" : parent, limit);
    }
}
