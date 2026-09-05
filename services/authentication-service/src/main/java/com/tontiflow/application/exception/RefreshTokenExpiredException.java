package com.tontiflow.application.exception;

/**
 * Levée lorsqu'un Refresh Token présenté existe mais est expiré.
 */
public class RefreshTokenExpiredException extends RuntimeException {

    public RefreshTokenExpiredException(String message) {
        super(message);
    }
}
