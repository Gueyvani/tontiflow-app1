package com.tontiflow.application.exception;

/**
 * Levée lorsqu'un {@code AuthAccount} référencé par son identifiant n'existe
 * pas, dans le contexte de l'administration RBAC (endpoints {@code /api/v1/admin/**}).
 *
 * <p>Distincte de {@link AccountNotFoundException}, qui est réservée au flux
 * de connexion (login) et mappée en 401 avec un message générique afin
 * d'empêcher l'énumération de comptes. Ici, l'appelant est un administrateur
 * déjà authentifié et autorisé : la réponse appropriée est 404 (ressource
 * introuvable), pas une erreur d'authentification.</p>
 */
public class AccountNotFoundInAdminException extends RuntimeException {

    public AccountNotFoundInAdminException(String message) {
        super(message);
    }
}
