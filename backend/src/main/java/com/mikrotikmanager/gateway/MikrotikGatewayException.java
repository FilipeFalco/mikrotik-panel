package com.mikrotikmanager.gateway;

public class MikrotikGatewayException extends RuntimeException {
    public MikrotikGatewayException(String message) {
        super(message);
    }

    public MikrotikGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
