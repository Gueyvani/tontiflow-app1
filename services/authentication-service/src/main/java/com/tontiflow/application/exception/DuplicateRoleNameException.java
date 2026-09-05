package com.tontiflow.application.exception;

/**
 * Levée lors de la création d'un {@code Role} dont le nom est déjà utilisé.
 */
public class DuplicateRoleNameException extends RuntimeException {

    public DuplicateRoleNameException(String message) {
        super(message);
    }
}
