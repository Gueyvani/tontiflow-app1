package com.tontiflow.application.exception;

/**
 * Levée lorsqu'un {@code Role} référencé par son identifiant n'existe pas.
 */
public class RoleNotFoundException extends RuntimeException {

    public RoleNotFoundException(String message) {
        super(message);
    }
}
