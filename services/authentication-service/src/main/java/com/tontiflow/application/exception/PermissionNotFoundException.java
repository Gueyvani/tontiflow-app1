package com.tontiflow.application.exception;

/**
 * Levée lorsqu'une {@code Permission} référencée par son identifiant n'existe pas.
 */
public class PermissionNotFoundException extends RuntimeException {

    public PermissionNotFoundException(String message) {
        super(message);
    }
}
