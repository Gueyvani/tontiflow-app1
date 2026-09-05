package com.tontiflow.application.exception;

/**
 * Levée lorsqu'aucun {@code AuthAccount} ne correspond à l'email recherché
 * lors d'une authentification.
 *
 * <p>Le message reste volontairement sobre (aucun email ni mot de passe) —
 * le mapping HTTP (ex. 401 uniforme quelle que soit la cause exacte, pour
 * éviter l'énumération de comptes) sera traité dans une phase ultérieure.</p>
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
