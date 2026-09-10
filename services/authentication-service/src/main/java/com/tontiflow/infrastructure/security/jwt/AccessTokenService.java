package com.tontiflow.infrastructure.security.jwt;

import com.tontiflow.UserContext;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Signe et valide les Access Token JWT (RS256) de {@code authentication-service}.
 *
 * <p>Point unique de génération/validation de l'Access Token du module :
 * il réutilise strictement le vocabulaire partagé de {@code security-common}
 * ({@link JwtClaimNames} pour les noms de claims, {@link UserContext} comme
 * contrat d'entrée/sortie) afin que tout consommateur du token (API Gateway,
 * autres microservices) interprète les claims de façon identique, conformément
 * au contrat documenté dans
 * {@code shared/security-common/docs/security/jwt-contract.md}.</p>
 *
 * <p><strong>Phase 2B-03-02-02</strong> : cette classe n'est pas encore un
 * bean Spring — elle est instanciée manuellement avec ses dépendances
 * (clés déjà résolues, configuration, horloge). Le câblage Spring
 * (chargement des clés via {@link RsaKeyLoader}, lecture d'{@code application.yml})
 * est différé à une sous-étape ultérieure.</p>
 */
public final class AccessTokenService {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final JwtProperties properties;
    private final Clock clock;

    /**
     * @param privateKey clé privée RSA utilisée pour signer les tokens émis
     * @param publicKey  clé publique RSA utilisée pour valider les tokens reçus
     * @param properties configuration de l'émetteur et de la durée de vie du token
     * @param clock      horloge utilisée pour dater et expirer les tokens
     *                   (permet des tests déterministes via {@link Clock#fixed})
     */
    public AccessTokenService(PrivateKey privateKey, PublicKey publicKey, JwtProperties properties, Clock clock) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Génère un Access Token JWT RS256 représentant le contexte utilisateur fourni.
     *
     * <p>Chaque claim est écrit exclusivement via les constantes de
     * {@link JwtClaimNames} : aucun nom de claim n'est codé en dur ici.</p>
     *
     * @param userContext contexte de l'utilisateur authentifié à encoder dans le token
     * @return le JWT signé, sérialisé au format compact
     */
    public String generate(UserContext userContext) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, userContext.userId().toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(issuedAt))
                .claim(JwtClaimNames.EXPIRATION, Date.from(expiresAt))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, properties.issuer())
                .claim(JwtClaimNames.USERNAME, userContext.username())
                .claim(JwtClaimNames.EMAIL, userContext.email())
                .claim(JwtClaimNames.ROLES, List.copyOf(userContext.roles()))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(userContext.permissions()))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Valide la signature et l'expiration d'un Access Token, puis reconstruit
     * le {@link UserContext} porté par ses claims.
     *
     * <p>La vérification (signature RS256, expiration) est déléguée entièrement
     * à la librairie JJWT — aucune erreur cryptographique n'est interceptée ni
     * masquée ici : une signature invalide ou un token expiré remonte tel quel
     * un {@link io.jsonwebtoken.JwtException} (ou une sous-classe, ex.
     * {@link io.jsonwebtoken.ExpiredJwtException},
     * {@link io.jsonwebtoken.security.SignatureException}) à l'appelant.</p>
     *
     * @param token Access Token JWT compact à valider
     * @return le {@link UserContext} reconstruit à partir des claims validés
     * @throws io.jsonwebtoken.JwtException si le token est invalide, mal signé ou expiré
     */
    public UserContext validate(String token) {
        // L'horloge du parseur est alignée sur celle du service pour que la
        // vérification d'expiration reste déterministe dans les tests.
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String subject = claims.get(JwtClaimNames.SUBJECT, String.class);
        if (subject == null || subject.isBlank()) {
            throw new MalformedJwtException("JWT subject absent ou vide");
        }
        final UUID userId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new MalformedJwtException("JWT subject invalide");
        }
        String username = claims.get(JwtClaimNames.USERNAME, String.class);
        String email = claims.get(JwtClaimNames.EMAIL, String.class);
        Set<String> roles = readStringSet(claims, JwtClaimNames.ROLES);
        Set<String> permissions = readStringSet(claims, JwtClaimNames.PERMISSIONS);

        return new UserContext(userId, username, email, roles, permissions);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readStringSet(Claims claims, String claimName) {
        List<String> values = claims.get(claimName, List.class);
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
