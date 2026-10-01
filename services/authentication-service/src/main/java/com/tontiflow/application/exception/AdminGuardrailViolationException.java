package com.tontiflow.application.exception;

/**
 * Levée lorsqu'une opération administrative violerait un garde-fou d'intégrité de l'ensemble des
 * administrateurs (décision F-4) : un administrateur ne peut pas se verrouiller, se désactiver ni
 * se retirer {@code ROLE_ADMIN}, et l'opération ne doit jamais laisser zéro administrateur actif.
 *
 * <p>Le message ne précise jamais quelle règle a joué : la réponse HTTP est un 409 générique fixe
 * (voir {@code GlobalExceptionHandler}). Levée avant toute écriture.</p>
 */
public class AdminGuardrailViolationException extends RuntimeException {

    public AdminGuardrailViolationException(String message) {
        super(message);
    }
}
