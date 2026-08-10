package com.mikrotikmanager.domain;

/** Read-only firewall address-list entry kept for future blocking analysis. */
public record RouterAddressListEntry(
        String id,
        String listName,
        String address,
        String comment,
        boolean disabled,
        boolean dynamic
) {
}
