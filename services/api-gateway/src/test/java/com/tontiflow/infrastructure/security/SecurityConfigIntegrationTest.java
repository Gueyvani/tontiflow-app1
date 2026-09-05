package com.tontiflow.infrastructure.security;

import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration de la chaîne de sécurité JWT du Gateway ({@link SecurityConfig}).
 *
 * <p>Le backend réel ({@code authentication-service}, {@code tontine-service}, ...)
 * n'est jamais démarré ici : les assertions portent uniquement sur la décision
 * d'authentification prise par le Gateway lui-même, jamais sur le résultat du
 * proxy vers un service en aval (qui peut légitimement échouer/timeout dans
 * cet environnement de test, sans rapport avec la sécurité).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GatewayJwtTestSecurityConfiguration.class)
class SecurityConfigIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KeyPair gatewayJwtTestKeyPair;

    // --- Décision R11 : /actuator/health public, endpoints sensibles non exposés ---

    @Test
    void health_withoutToken_isPubliclyAccessible() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void sensitiveActuatorEndpoint_remainsProtectedAndUnexposed() {
        // Sans JWT : rejete par la chaine de securite avant meme la question du routage.
        webTestClient.get().uri("/actuator/beans")
                .exchange()
                .expectStatus().isUnauthorized();

        // Avec un JWT valide : toujours inaccessible - management.endpoints.web.exposure.include
        // (application.yml) ne liste que "health".
        String validToken = buildToken(Instant.now().plus(15, ChronoUnit.MINUTES));
        webTestClient.get().uri("/actuator/beans")
                .header("Authorization", "Bearer " + validToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void register_withoutToken_isNotRejectedByGatewaySecurity() {
        webTestClient.post().uri("/api/v1/auth/register")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    @Test
    void login_withoutToken_isNotRejectedByGatewaySecurity() {
        webTestClient.post().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    @Test
    void refresh_withoutToken_isNotRejectedByGatewaySecurity() {
        webTestClient.post().uri("/api/v1/auth/refresh")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    @Test
    void protectedRoute_withoutToken_isRejectedWithUnauthorized() {
        webTestClient.get().uri("/api/v1/tontines/anything")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRoute_withInvalidToken_isRejectedWithUnauthorized() {
        webTestClient.get().uri("/api/v1/tontines/anything")
                .header("Authorization", "Bearer ceci-n-est-pas-un-jwt-valide")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRoute_withExpiredToken_isRejectedWithUnauthorized() {
        String expiredToken = buildToken(Instant.now().minus(1, ChronoUnit.MINUTES));

        webTestClient.get().uri("/api/v1/tontines/anything")
                .header("Authorization", "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRoute_withValidToken_isNotRejectedByGatewaySecurity() {
        String validToken = buildToken(Instant.now().plus(15, ChronoUnit.MINUTES));

        webTestClient.get().uri("/api/v1/tontines/anything")
                .header("Authorization", "Bearer " + validToken)
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    private String buildToken(Instant expiresAt) {
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(expiresAt))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_USER"))
                .claim(JwtClaimNames.PERMISSIONS, List.of("TONTINE_READ"))
                .signWith(gatewayJwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
