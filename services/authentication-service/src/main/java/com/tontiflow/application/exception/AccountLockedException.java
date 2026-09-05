package com.tontiflow.application.exception;

/**
 * Levée lorsqu'une authentification est tentée sur un compte au statut
 * {@link com.tontiflow.domain.enums.AccountStatus#LOCKED}.
 */
public class AccountLockedException extends RuntimeException {

    public AccountLockedException(String message) {
        super(message);
    }
}
