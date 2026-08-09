package com.mikrotikmanager.gateway.routeros;

/** Categorizes a sanitized failure while reading RouterOS REST resources. */
public enum RouterOsRestErrorType {
    AUTHENTICATION_FAILED,
    UNAVAILABLE,
    BAD_RESPONSE,
    TLS_ERROR
}
