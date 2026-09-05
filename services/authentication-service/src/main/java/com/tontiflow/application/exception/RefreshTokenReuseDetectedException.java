package com.tontiflow.application.exception;

/**
 * Levée lorsqu'un Refresh Token déjà révoqué est présenté à nouveau — signal
 * probable de compromission (vol de token). Sa levée s'accompagne toujours
 * de la révocation de la famille entière du token concerné.
 */
public class RefreshTokenReuseDetectedException extends RuntimeException {

    public RefreshTokenReuseDetectedException(String message) {
        super(message);
    }
}
