package com.mikrotikmanager.gateway;

/** Categorises a safe RouterOS gateway failure without exposing response bodies. */
public enum GatewayErrorType {
    UNAVAILABLE,
    AUTHENTICATION_FAILED,
    BAD_RESPONSE,
    TLS_ERROR,
    WRITE_NOT_IMPLEMENTED
}
