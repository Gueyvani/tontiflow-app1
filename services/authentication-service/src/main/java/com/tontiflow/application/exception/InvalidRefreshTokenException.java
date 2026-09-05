package com.tontiflow.application.exception;

/**
 * Levée lorsqu'un Refresh Token présenté ne correspond à aucun token connu
 * (hash introuvable).
 *
 * <p>Mappée sur le même message HTTP générique que
 * {@link RefreshTokenExpiredException} et {@link RefreshTokenReuseDetectedException}
 * afin de ne jamais laisser un appelant distinguer la cause exacte de l'échec.</p>
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
