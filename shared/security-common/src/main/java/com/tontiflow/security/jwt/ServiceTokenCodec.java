package com.tontiflow.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Jeton d'authentification <b>service-à-service</b> (décision F-8) : émis par
 * un service appelant pour s'identifier auprès d'un service interne, jamais
 * pour représenter un utilisateur. Distinct du JWT utilisateur (RS256, émis
 * par {@code authentication-service}) : algorithme HS256 à secret partagé,
 * durée de vie de {@value #TOKEN_TTL_SECONDS} s, {@code aud} obligatoire.
 *
 * <p>Claims : {@code iss} (service émetteur), {@code aud} (service
 * destinataire), {@code sub} ({@code service:<iss>}), {@code iat}, {@code exp},
 * {@code jti}, {@code scope} (portées séparées par un espace) et, à titre
 * d'<b>audit uniquement</b>, {@code on_behalf_of} (identifiant de
 * l'utilisateur à l'origine de l'appel). {@code on_behalf_of} ne doit jamais
 * servir à autoriser quoi que ce soit côté destinataire : l'autorisation
 * métier reste celle du service appelant.</p>
 *
 * <p>Le secret n'est jamais journalisé ni renvoyé dans un message d'erreur.
 * Un JWT utilisateur (RS256) ou tout jeton non HS256 est rejeté.</p>
 */
public final class ServiceTokenCodec {

    public static final String SERVICE_TONTINE = "tontine-service";
    public static final String SERVICE_FINANCIAL = "financial-service";

    public static final String SCOPE_LEDGER_READ = "ledger.read";
    public static final String SCOPE_LEDGER_WRITE = "ledger.write";

    public static final int TOKEN_TTL_SECONDS = 60;
    public static final int MIN_SECRET_BYTES = 32;

    private static final long CLOCK_SKEW_SECONDS = 30;
    private static final String CLAIM_SCOPE = "scope";
    private static final String CLAIM_ON_BEHALF_OF = "on_behalf_of";
    private static final String SUBJECT_PREFIX = "service:";
    private static final String EXPECTED_ALGORITHM = "HS256";

    private final SecretKey key;
    private final Clock clock;

    /**
     * @param secret secret partagé, d'au moins {@value #MIN_SECRET_BYTES} octets (UTF-8)
     * @throws IllegalArgumentException si le secret est absent ou trop court (échec de démarrage voulu)
     */
    public ServiceTokenCodec(String secret, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "Le secret de jeton service-a-service est absent ou trop court (minimum "
                            + MIN_SECRET_BYTES + " octets)");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** Claims vérifiés d'un jeton de service valide. */
    public record ServiceTokenClaims(String issuer, String subject, Set<String> scopes,
                                     UUID onBehalfOf, String tokenId) {
    }

    /**
     * Émet un jeton de service court.
     *
     * @param onBehalfOf utilisateur à l'origine de l'appel (audit uniquement), peut être {@code null}
     */
    public String issue(String issuer, String audience, Collection<String> scopes, UUID onBehalfOf) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(SUBJECT_PREFIX + issuer)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofSeconds(TOKEN_TTL_SECONDS))))
                .claim(CLAIM_SCOPE, String.join(" ", scopes));
        if (onBehalfOf != null) {
            builder.claim(CLAIM_ON_BEHALF_OF, onBehalfOf.toString());
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    /**
     * Vérifie un jeton de service : signature HS256, émetteur, destinataire,
     * expiration (tolérance {@value #CLOCK_SKEW_SECONDS} s), durée de vie bornée et sujet.
     *
     * @throws JwtException si le jeton est invalide, expiré, mal signé, d'un autre algorithme,
     *                      d'un autre émetteur/destinataire ou de durée de vie excessive
     */
    public ServiceTokenClaims verify(String token, String expectedIssuer, String expectedAudience) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(expectedIssuer)
                .requireAudience(expectedAudience)
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build()
                .parseSignedClaims(token);

        if (!EXPECTED_ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
            throw new JwtException("Algorithme de jeton de service inattendu");
        }
        Claims claims = jws.getPayload();
        if (!(SUBJECT_PREFIX + expectedIssuer).equals(claims.getSubject())) {
            throw new JwtException("Sujet de jeton de service invalide");
        }
        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();
        if (issuedAt == null || expiration == null
                || expiration.getTime() - issuedAt.getTime() > Duration.ofSeconds(TOKEN_TTL_SECONDS).toMillis()) {
            throw new JwtException("Duree de vie de jeton de service invalide");
        }

        Set<String> scopes = new LinkedHashSet<>();
        String rawScope = claims.get(CLAIM_SCOPE, String.class);
        if (rawScope != null && !rawScope.isBlank()) {
            scopes.addAll(Arrays.asList(rawScope.trim().split("\\s+")));
        }
        UUID onBehalfOf = null;
        String rawOnBehalfOf = claims.get(CLAIM_ON_BEHALF_OF, String.class);
        if (rawOnBehalfOf != null) {
            try {
                onBehalfOf = UUID.fromString(rawOnBehalfOf);
            } catch (IllegalArgumentException e) {
                throw new JwtException("on_behalf_of invalide");
            }
        }
        return new ServiceTokenClaims(claims.getIssuer(), claims.getSubject(), Set.copyOf(scopes), onBehalfOf,
                claims.getId());
    }
}
