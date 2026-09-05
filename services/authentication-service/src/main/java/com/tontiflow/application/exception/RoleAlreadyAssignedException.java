package com.tontiflow.application.exception;

/**
 * Levée lors de la tentative d'attribuer à un {@code AuthAccount} un
 * {@code Role} qu'il possède déjà.
 */
public class RoleAlreadyAssignedException extends RuntimeException {

    public RoleAlreadyAssignedException(String message) {
        super(message);
    }
}
