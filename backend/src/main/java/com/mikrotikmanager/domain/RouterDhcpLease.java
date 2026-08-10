package com.mikrotikmanager.domain;

/** Read-only DHCP lease facts used by dry-run preconditions. */
public record RouterDhcpLease(
        String id,
        String macAddress,
        String ipAddress,
        String server,
        String interfaceName,
        String status,
        boolean blockAccess,
        String comment,
        boolean dynamic,
        boolean disabled
) {
    public boolean isBound() {
        return !disabled && "bound".equalsIgnoreCase(status);
    }
}
