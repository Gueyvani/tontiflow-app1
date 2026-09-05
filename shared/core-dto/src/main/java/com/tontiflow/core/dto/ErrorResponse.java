package com.tontiflow.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Structure canonique des erreurs HTTP (RFC 7807 Problem Details).
 * Ce DTO est partage entre tous les services pour uniformiser la reponse d'erreur API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String correlationId,
        Instant timestamp
) {
    public static ErrorResponse of(String title, int status, String detail, String instance, String correlationId) {
        return new ErrorResponse(
                "about:blank",
                title,
                status,
                detail,
                instance,
                correlationId,
                Instant.now()
        );
    }
}