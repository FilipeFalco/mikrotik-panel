package com.mikrotikmanager.support;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final ApiErrorCode code;
    private final HttpStatus status;

    public ApiException(ApiErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ApiErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
