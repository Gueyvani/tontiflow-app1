package com.tontiflow.application.exception;

/**
 * Levée lors de la tentative de retirer d'un {@code AuthAccount} un
 * {@code Role} qu'il ne possède pas.
 */
public class RoleNotAssignedException extends RuntimeException {

    public RoleNotAssignedException(String message) {
        super(message);
    }
}
