package com.tontiflow.infrastructure.security;

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
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration de la chaîne de sécurité JWT de {@code credit-service}
 * ({@link SecurityConfig}).
 *
 * <p>{@code credit-service} n'a aucun contrôleur métier ({@link
 * #PROTECTED_PATH} utilise donc un endpoint Actuator non exposé —
 * {@code /actuator/beans} — comme substitut représentatif d'une ressource
 * protégée). Historiquement exercée via {@code /actuator/health} lui-même ;
 * retargetée en décision R11 (corrections techniques) car {@code
 * /actuator/health} est désormais volontairement public (sonde
 * d'orchestration sans JWT) — voir {@link #health_withoutToken_isPubliclyAccessible}
 * ci-dessous.</p>
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
    private KeyPair jwtTestKeyPair;

    // --- Décision R11 : /actuator/health public ---

    @Test
    void health_withoutToken_isPubliclyAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Matrice JWT / chaîne de sécurité complète (endpoint protégé) ---

    @Test
    void protectedEndpoint_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(PROTECTED_PATH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withValidToken_isNeverExposed() {
        String token = validToken(Set.of("ROLE_USER"), Set.of());

        ResponseEntity<String> response = exchangeWithBearer(token);

        // Authentifie correctement, mais /actuator/beans n'est jamais enregistre
        // (management.endpoints.web.exposure.include ne liste que "health").
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void protectedEndpoint_withMalformedToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = exchangeWithBearer("ceci-n-est-pas-un-jwt-valide");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withExpiredToken_isRejectedWithUnauthorized() {
        String token = tokenExpiringAt(Instant.now().minus(1, ChronoUnit.MINUTES));

        ResponseEntity<String> response = exchangeWithBearer(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withWrongSigningKey_isRejectedWithUnauthorized() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey otherPrivateKey = generator.generateKeyPair().getPrivate();

        String token = Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_USER"))
                .claim(JwtClaimNames.PERMISSIONS, List.of())
                .signWith(otherPrivateKey, Jwts.SIG.RS256)
                .compact();

        ResponseEntity<String> response = exchangeWithBearer(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withBasicSchemeInsteadOfBearer_isRejectedWithUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic dXNlcjpwYXNz");
        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withEmptyBearer_isRejectedWithUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer ");
        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- Tests d'usurpation ---

    @Test
    void protectedEndpoint_withValidTokenAndForgedUserIdHeader_stillAuthenticatesFromJwtOnly() {
        assertNotUnauthorizedWithForgedHeader("X-User-Id", "utilisateur-admin");
    }

    @Test
    void protectedEndpoint_withValidTokenAndForgedUsernameHeader_stillAuthenticatesFromJwtOnly() {
        assertNotUnauthorizedWithForgedHeader("X-Username", "admin");
    }

    @Test
    void protectedEndpoint_withValidTokenAndForgedEmailHeader_stillAuthenticatesFromJwtOnly() {
        assertNotUnauthorizedWithForgedHeader("X-Email", "admin@example.com");
    }

    @Test
    void protectedEndpoint_withValidTokenAndForgedRolesHeader_stillAuthenticatesFromJwtOnly() {
        assertNotUnauthorizedWithForgedHeader("X-Roles", "ROLE_ADMIN");
    }

    @Test
    void protectedEndpoint_withValidTokenAndForgedPermissionsHeader_stillAuthenticatesFromJwtOnly() {
        assertNotUnauthorizedWithForgedHeader("X-Permissions", "*");
    }

    @Test
    void protectedEndpoint_withForgedTrustHeadersButNoJwt_isRejectedWithUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "utilisateur-admin");
        headers.set("X-Username", "admin");
        headers.set("X-Email", "admin@example.com");
        headers.set("X-Roles", "ROLE_ADMIN");
        headers.set("X-Permissions", "*");

        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void assertNotUnauthorizedWithForgedHeader(String headerName, String headerValue) {
        String token = validToken(Set.of("ROLE_USER"), Set.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(headerName, headerValue);

        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // Comportement identique au cas sans header forge : preuve que ce header
        // n'a strictement aucun effet, seule l'identite du JWT compte. Un utilisateur
        // non-admin (aucun ROLE_ADMIN dans le JWT reel) ne devient jamais admin via ce header.
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> exchangeWithBearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(PROTECTED_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String validToken(Set<String> roles, Set<String> permissions) {
        return tokenExpiringAt(Instant.now().plus(15, ChronoUnit.MINUTES), roles, permissions);
    }

    private String tokenExpiringAt(Instant expiresAt) {
        return tokenExpiringAt(expiresAt, Set.of("ROLE_USER"), Set.of());
    }

    private String tokenExpiringAt(Instant expiresAt, Set<String> roles, Set<String> permissions) {
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(expiresAt))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(roles))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(permissions))
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
