package com.tontiflow.infrastructure.security;

import com.tontiflow.UserContext;
import com.tontiflow.infrastructure.security.jwt.AccessTokenService;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration de la chaîne de sécurité JWT ({@link SecurityConfig}).
 *
 * <p>{@code authentication-service} n'a aucun endpoint authentifié-mais-pas-
 * admin ({@code /api/v1/auth/**} est public, {@code /api/v1/admin/**} exige
 * {@code ROLE_ADMIN}) : {@link #PROTECTED_PATH} utilise donc un endpoint
 * Actuator non exposé ({@code /actuator/beans}) comme substitut
 * représentatif d'une ressource protégée par simple authentification.
 * Historiquement exercée via {@code /actuator/health} lui-même ; retargetée
 * en décision R11 (corrections techniques) car {@code /actuator/health} est
 * désormais volontairement public (sonde d'orchestration sans JWT, périmant
 * la décision précédente documentée dans {@link SecurityConfig}) — voir
 * {@link #health_withoutToken_isPubliclyAccessible} ci-dessous.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class SecurityConfigIntegrationTest {

    /** Endpoint Actuator sensible, jamais exposé, toujours protégé. */
    private static final String PROTECTED_PATH = "/actuator/beans";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private KeyPair jwtTestKeyPair;

    @Test
    void health_withoutToken_isPubliclyAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void protectedEndpoint_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(PROTECTED_PATH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withValidToken_isNeverExposed() {
        String token = accessTokenService.generate(sampleUserContext());

        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED_PATH, HttpMethod.GET, withBearerToken(token), String.class);

        // Authentifie correctement, mais /actuator/beans n'est jamais enregistre
        // (management.endpoints.web.exposure.include ne liste que "health").
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void protectedEndpoint_withInvalidToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED_PATH, HttpMethod.GET, withBearerToken("ceci-n-est-pas-un-jwt-valide"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- Durcissement R21-B.1 : claim "sub" absent ou non-UUID -> 401 (pas 500) ---

    @Test
    void protectedEndpoint_withSignedTokenButSubjectAbsent_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED_PATH, HttpMethod.GET, withBearerToken(signedTokenWithSubject(null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withSignedTokenButSubjectNotUuid_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED_PATH, HttpMethod.GET, withBearerToken(signedTokenWithSubject("pas-un-uuid")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Token RS256 correctement signé (clé de test) mais dont le claim
     * {@code sub} est soit absent ({@code subject == null}), soit une valeur
     * arbitraire non-UUID.
     */
    private String signedTokenWithSubject(String subject) {
        var builder = Jwts.builder()
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_USER"))
                .claim(JwtClaimNames.PERMISSIONS, List.of());
        if (subject != null) {
            builder.claim(JwtClaimNames.SUBJECT, subject);
        }
        return builder.signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static HttpEntity<Void> withBearerToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private static UserContext sampleUserContext() {
        return new UserContext(
                UUID.randomUUID(),
                "alice",
                "alice@tontiflow.test",
                Set.of("ROLE_USER"),
                Set.of("TONTINE_READ")
        );
    }
}
