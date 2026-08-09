package com.mikrotikmanager.domain;

/**
 * Local presentation role for a managed RouterOS interface.
 *
 * <p>This metadata is stored only in the panel's SQLite database. It never
 * changes RouterOS routing, NAT, DHCP, interface lists, or any other RouterOS
 * configuration.</p>
 */
public enum ManagedPortRole {
    WAN,
    CLIENT
}
