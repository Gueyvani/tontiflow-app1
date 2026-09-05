package com.tontiflow.application.exception;

/**
 * Levée lorsque le mot de passe fourni ne correspond pas au hash stocké
 * du compte ({@code AuthAccount.passwordHash}).
 *
 * <p>Ne porte jamais le mot de passe (ni en clair, ni haché) dans son message.</p>
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
