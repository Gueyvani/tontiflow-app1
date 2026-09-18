package com.tontiflow.application.exception;

import com.tontiflow.domain.enums.AccountStatus;

/**
 * Levée lorsqu'une transition administrative de {@link AccountStatus} est
 * demandée depuis un état source vers lequel elle n'est pas autorisée
 * (décision R21-RD, D2) — y compris lorsque cette invalidité n'est révélée
 * qu'après relecture consécutive à une course concurrente perdue (voir
 * {@code AuthAccountService#changeAccountStatus}).
 *
 * <p>Ne contient jamais le motif de la transition demandée (uniquement les
 * statuts, information déjà connue de l'appelant administrateur).</p>
 */
public class InvalidAccountStatusTransitionException extends RuntimeException {

    public InvalidAccountStatusTransitionException(String message) {
        super(message);
    }
}
