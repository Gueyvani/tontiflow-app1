package com.tontiflow.application.exception;

/**
 * Levée lors de la création d'un compte dont l'email est déjà utilisé
 * par un {@code AuthAccount} existant.
 *
 * <p>Ne porte jamais le mot de passe fourni dans son message.</p>
 */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String message) {
        super(message);
    }
}
