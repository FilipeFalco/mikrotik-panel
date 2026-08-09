package com.mikrotikmanager.support;

import com.mikrotikmanager.gateway.MikrotikGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MikrotikGatewayException.class)
    ResponseEntity<ErrorResponse> handleGatewayException(MikrotikGatewayException exception) {
        log.warn("MikroTik gateway operation failed type={}", exception.errorType());
        return switch (exception.errorType()) {
            case AUTHENTICATION_FAILED -> response(HttpStatus.UNAUTHORIZED, ApiErrorCode.MIKROTIK_AUTHENTICATION_FAILED,
                    "A autenticação no MikroTik falhou ou o usuário não possui permissão de leitura.");
            case BAD_RESPONSE -> response(HttpStatus.BAD_GATEWAY, ApiErrorCode.MIKROTIK_BAD_RESPONSE,
                    "O MikroTik retornou uma resposta de leitura inesperada.");
            case TLS_ERROR -> response(HttpStatus.BAD_GATEWAY, ApiErrorCode.MIKROTIK_TLS_ERROR,
                    "Não foi possível validar a conexão TLS com o MikroTik.");
            case WRITE_NOT_IMPLEMENTED -> response(HttpStatus.FORBIDDEN, ApiErrorCode.MIKROTIK_WRITES_DISABLED,
                    "A escrita RouterOS não é implementada na Fase 2.");
            case UNAVAILABLE -> response(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.MIKROTIK_UNAVAILABLE,
                    "Não foi possível comunicar com o MikroTik.");
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null ? "Dados inválidos." : fieldError.getDefaultMessage();
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_INPUT, message);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("Unexpected application error", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "Ocorreu um erro inesperado.");
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, ApiErrorCode code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code.name(), message, Instant.now()));
    }
}
