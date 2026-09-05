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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration de la chaîne de sécurité JWT de {@code financial-service}
 * ({@link SecurityConfig}).
 *
 * <p>Exercée via {@link #PROTECTED_PATH} (endpoint interne réel, authentifié,
 * sans effet de bord). Historiquement exercée via {@code /actuator/health}
 * (seul endpoint HTTP existant avant les décisions R3+) ; retargetée en
 * décision R11 (corrections techniques) car {@code /actuator/health} est
 * désormais volontairement public (sonde d'orchestration sans JWT) — voir
 * {@link #health_withoutToken_isPubliclyAccessible} et {@link
 * #sensitiveActuatorEndpoint_remainsProtectedAndUnexposed} ci-dessous pour
 * les preuves spécifiques à ce changement.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class SecurityConfigIntegrationTest {

    /** Endpoint réel authentifié, sans effet de bord, jamais public. */
    private static final String PROTECTED_PATH = "/internal/accounts/1/TONTINE/balance";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KeyPair jwtTestKeyPair;

    // --- Décision R11 : /actuator/health public, endpoints sensibles non exposés ---

    @Test
    void health_withoutToken_isPubliclyAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sensitiveActuatorEndpoint_remainsProtectedAndUnexposed() {
        // Sans JWT : la chaine de securite rejette avant meme de determiner si
        // /actuator/beans est mappe (anyRequest().authenticated() intercepte
        // toute URL, y compris les endpoints actuator non exposes).
        ResponseEntity<String> withoutToken = restTemplate.getForEntity("/actuator/beans", String.class);
        assertThat(withoutToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Avec un JWT valide : toujours inaccessible - management.endpoints.web.exposure.include
        // ne liste que "health" (application.yaml), "beans" n'est jamais enregistre.
        String token = validToken(Set.of("ROLE_USER"), Set.of());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> withToken = restTemplate.exchange(
                "/actuator/beans", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(withToken.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Matrice JWT / chaîne de sécurité complète (endpoint métier protégé) ---

    @Test
    void protectedEndpoint_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(PROTECTED_PATH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withValidToken_isAuthenticated() {
        String token = validToken(Set.of("ROLE_USER"), Set.of());

        ResponseEntity<String> response = exchangeWithBearer(token);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
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
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.PrivateKey otherPrivateKey = generator.generateKeyPair().getPrivate();

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
        // n'a strictement aucun effet, seule l'identite du JWT compte.
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
