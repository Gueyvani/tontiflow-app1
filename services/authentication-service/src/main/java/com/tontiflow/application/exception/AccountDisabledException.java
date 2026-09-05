package com.tontiflow.application.exception;

/**
 * Levée lorsqu'une authentification est tentée sur un compte au statut
 * {@link com.tontiflow.domain.enums.AccountStatus#DISABLED}.
 */
public class AccountDisabledException extends RuntimeException {

    public AccountDisabledException(String message) {
        super(message);
    }
}
