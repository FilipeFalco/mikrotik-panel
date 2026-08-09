package com.mikrotikmanager.domain;

/** A single RouterOS capability checked on demand by the gateway. */
public record GatewayDiagnosticCheck(String name, boolean available, String detail) {
}
