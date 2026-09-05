package com.tontiflow.security.jwt;

/**
 * Constantes représentant les noms des claims JWT utilisés
 * dans l'écosystème TontiFlow.
 */
public final class JwtClaimNames {

    /**
     * Constructeur privé empêchant l'instanciation.
     */
    private JwtClaimNames() {
        throw new IllegalStateException("Utility class");
    }

    /** Identifiant unique de l'utilisateur. */
    public static final String SUBJECT = "sub";

    /** Date d'émission du token. */
    public static final String ISSUED_AT = "iat";

    /** Date d'expiration du token. */
    public static final String EXPIRATION = "exp";

    /** Identifiant unique du token. */
    public static final String JWT_ID = "jti";

    /** Émetteur du token. */
    public static final String ISSUER = "iss";

    /** Nom d'utilisateur. */
    public static final String USERNAME = "username";

    /** Adresse email. */
    public static final String EMAIL = "email";

    /** Rôles de l'utilisateur. */
    public static final String ROLES = "roles";

    /** Permissions de l'utilisateur. */
    public static final String PERMISSIONS = "permissions";
}