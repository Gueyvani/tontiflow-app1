package com.tontiflow.infrastructure.ratelimit;

import com.tontiflow.infrastructure.security.GatewayJwtTestSecurityConfiguration;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prouve que le rate limiting IP du Gateway ({@link ClaimIpRateLimitFilter})
 * rejette en 429 <b>avant</b> tout proxy vers {@code tontine-service}
 * (jamais démarré ici) : les requêtes sous le seuil échouent seulement parce
 * que le backend est injoignable — jamais avec un 429 — tandis que la
 * requête au-delà du seuil reçoit un 429 émis par le Gateway lui-même.
 *
 * <p>Seuil volontairement bas via {@code @SpringBootTest properties}. Toutes
 * les requêtes proviennent de {@code 127.0.0.1} → même compteur IP.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"claim-ip-rate-limit.ip-per-minute=3", "claim-ip-rate-limit.trusted-proxy-count=0"})
@ActiveProfiles("test")
@Import(GatewayJwtTestSecurityConfiguration.class)
class ClaimIpRateLimitIntegrationTest {

    private static final String CLAIM_PATH = "/api/v1/tontines/1/members/claim";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KeyPair gatewayJwtTestKeyPair;

    @Test
    void claim_isRateLimitedByGateway_after429BackendIsNeverReached() {
        String token = buildToken(Instant.now().plus(15, ChronoUnit.MINUTES));

        // 3 premières requêtes : sous le seuil. Elles ne sont pas rejetées par
        // le rate limiter (le proxy vers tontine-service peut légitimement
        // échouer ici — backend absent — mais jamais avec un 429).
        for (int i = 0; i < 3; i++) {
            webTestClient.post().uri(CLAIM_PATH)
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().value(status ->
                            assertThat(status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));
        }

        // 4e requête (même IP) : rejetée par le Gateway, corps générique.
        webTestClient.post().uri(CLAIM_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After")
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("Trop de tentatives");
                    assertThat(body).doesNotContain("Invitation");
                });
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
