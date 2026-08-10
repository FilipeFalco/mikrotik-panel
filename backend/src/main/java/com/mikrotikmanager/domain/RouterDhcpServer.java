package com.mikrotikmanager.domain;

/** Read-only DHCP server state normalized away from the RouterOS REST DTO. */
public record RouterDhcpServer(
        String id,
        String name,
        String interfaceName,
        boolean disabled
) {
}
