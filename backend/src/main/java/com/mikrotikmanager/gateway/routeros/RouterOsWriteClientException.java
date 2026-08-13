package com.mikrotikmanager.gateway.routeros;

/**
 * Sanitized write transport failure. It never retains a response body,
 * headers, URL, credentials, or the underlying HTTP exception.
 */
public final class RouterOsWriteClientException extends RuntimeException {
    private final RouterOsWriteErrorType errorType;

    public RouterOsWriteClientException(RouterOsWriteErrorType errorType) {
        this(errorType, safeMessage(errorType));
    }

    RouterOsWriteClientException(RouterOsWriteErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public RouterOsWriteErrorType errorType() {
        return errorType;
    }

    private static String safeMessage(RouterOsWriteErrorType errorType) {
        return switch (errorType) {
            case CREDENTIALS_MISSING -> "RouterOS write credentials are not configured.";
            case DEVICE_BLOCK_WRITES_DISABLED -> "Device block writes are disabled.";
            case GLOBAL_WRITES_DISABLED -> "RouterOS writes are disabled.";
            case PERMISSION_DENIED -> "RouterOS rejected the write permission.";
            case NOT_FOUND -> "RouterOS resource was not found.";
            case REJECTED -> "RouterOS rejected the requested write.";
            case OUTCOME_UNKNOWN -> "RouterOS write outcome could not be confirmed.";
        };
    }
}
