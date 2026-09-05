package com.tontiflow.infrastructure.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration immuable nécessaire à la signature et à la validation
 * des Access Token JWT (RS256) de {@code authentication-service}.
 *
 * <p>Cette classe ne charge ni ne génère aucune clé : elle porte uniquement
 * les {@link Resource} pointant vers un matériel cryptographique externalisé
 * (fichier monté, variable d'environnement résolue en amont, etc.), à charger
 * ensuite via {@link RsaKeyLoader}.</p>
 *
 * <p><strong>Phase 2B-03-02-03</strong> : liée à la configuration Spring via
 * {@code @ConfigurationProperties(prefix = "jwt")}, bindée depuis la section
 * {@code jwt:} d'{@code application.yml} (production, via
 * {@link JwtSecurityConfiguration}). En test, elle continue d'être
 * instanciée manuellement par {@code JwtTestSecurityConfiguration}, sans
 * passer par le binding Spring ni par aucune clé réelle.</p>
 *
 * @param privateKeyResource ressource PEM (PKCS8) de la clé privée RSA de signature
 * @param publicKeyResource  ressource PEM (X509) de la clé publique RSA de validation
 * @param issuer             valeur du claim {@code iss} portée par les tokens émis
 * @param accessTokenTtl     durée de vie de l'Access Token, strictement positive
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        Resource privateKeyResource,
        Resource publicKeyResource,
        String issuer,
        Duration accessTokenTtl
) {

    /**
     * Constructeur compact validant les invariants de configuration :
     * aucune valeur nulle, émetteur non vide, durée de vie strictement positive.
     */
    public JwtProperties {
        Objects.requireNonNull(privateKeyResource, "privateKeyResource ne doit pas être null");
        Objects.requireNonNull(publicKeyResource, "publicKeyResource ne doit pas être null");
        Objects.requireNonNull(issuer, "issuer ne doit pas être null");
        Objects.requireNonNull(accessTokenTtl, "accessTokenTtl ne doit pas être null");

        if (issuer.isBlank()) {
            throw new IllegalArgumentException("issuer ne doit pas être vide");
        }
        if (accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("accessTokenTtl doit être strictement positif");
        }
    }
}
