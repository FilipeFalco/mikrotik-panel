package com.mikrotikmanager.gateway.routeros;

/**
 * A deliberately sanitized REST client failure.
 *
 * <p>The original transport exception is intentionally not retained as a
 * cause: HTTP client messages and response bodies are not safe to expose or
 * log because they can contain request or server-provided sensitive data.
 */
public final class RouterOsRestClientException extends RuntimeException {
    private final RouterOsRestErrorType errorType;

    RouterOsRestClientException(RouterOsRestErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public RouterOsRestErrorType errorType() {
        return errorType;
    }
}
