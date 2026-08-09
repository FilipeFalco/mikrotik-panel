package com.mikrotikmanager.gateway.routeros;

/**
 * Small immutable representation of an active FastTrack firewall rule for
 * diagnostics. It intentionally contains no raw JSON or RouterOS credentials.
 */
public record RouterOsFastTrackRule(String chain, String comment) {
}
