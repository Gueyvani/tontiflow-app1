package com.tontiflow.application.exception;

/**
 * Levée lors de la création d'une {@code Permission} dont le nom est déjà utilisé.
 */
public class DuplicatePermissionNameException extends RuntimeException {

    public DuplicatePermissionNameException(String message) {
        super(message);
    }
}
