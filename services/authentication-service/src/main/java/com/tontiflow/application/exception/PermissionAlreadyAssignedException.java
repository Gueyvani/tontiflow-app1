package com.tontiflow.application.exception;

/**
 * Levée lors de la tentative d'associer à un {@code Role} une
 * {@code Permission} qu'il possède déjà.
 */
public class PermissionAlreadyAssignedException extends RuntimeException {

    public PermissionAlreadyAssignedException(String message) {
        super(message);
    }
}
