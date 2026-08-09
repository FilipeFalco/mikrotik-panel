package com.mikrotikmanager.gateway;

public class MikrotikGatewayException extends RuntimeException {
    private final GatewayErrorType errorType;

    public MikrotikGatewayException(String message) {
        this(GatewayErrorType.UNAVAILABLE, message);
    }

    public MikrotikGatewayException(String message, Throwable cause) {
        this(GatewayErrorType.UNAVAILABLE, message, cause);
    }

    public MikrotikGatewayException(GatewayErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public MikrotikGatewayException(GatewayErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public GatewayErrorType errorType() {
        return errorType;
    }
}
