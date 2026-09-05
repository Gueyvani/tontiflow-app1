package com.tontiflow.interfaces.rest;

import com.tontiflow.core.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mappe les exceptions de {@code ContributionService}/{@code LedgerService}
 * vers des réponses HTTP uniformes ({@link ErrorResponse}, RFC 7807), même
 * convention qu'{@code authentication-service}/{@code tontine-service}
 * (décision R3 : ne pas inventer une nouvelle structure d'erreur).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return build(HttpStatus.BAD_REQUEST, detail, request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String detail, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                status.getReasonPhrase(),
                status.value(),
                detail,
                request.getRequestURI(),
                resolveCorrelationId(request)
        );
        return ResponseEntity.status(status).body(body);
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(CORRELATION_ID_HEADER);
        return (header == null || header.isBlank()) ? UUID.randomUUID().toString() : header;
    }
}
