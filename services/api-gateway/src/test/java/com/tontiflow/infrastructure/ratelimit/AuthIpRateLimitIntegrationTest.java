package com.tontiflow.infrastructure.ratelimit;

import com.tontiflow.infrastructure.security.GatewayJwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prouve que le rate limiting IP du Gateway sur {@code POST /api/v1/auth/login}
 * ({@link AuthIpRateLimitFilter}) rejette en 429 <b>avant</b> tout proxy vers
 * {@code authentication-service} (jamais démarré ici) : les requêtes sous le
 * seuil échouent seulement parce que le backend est injoignable — jamais avec
 * un 429 — tandis que la requête au-delà du seuil reçoit un 429 émis par le
 * Gateway lui-même.
 *
 * <p>{@code /api/v1/auth/login} est public au Gateway : aucun JWT requis.
 * Seuil volontairement bas via {@code @SpringBootTest properties}. Toutes les
 * requêtes proviennent de {@code 127.0.0.1} → même compteur IP.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"auth-ip-rate-limit.login-per-minute=3", "auth-ip-rate-limit.trusted-proxy-count=0"})
@ActiveProfiles("test")
@Import(GatewayJwtTestSecurityConfiguration.class)
class AuthIpRateLimitIntegrationTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String BODY = "{\"email\":\"a@b.test\",\"password\":\"x\"}";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void login_isRateLimitedByGateway_after429BackendIsNeverReached() {
        // 3 premières requêtes : sous le seuil. Non rejetées par le rate limiter
        // (le proxy vers authentication-service peut légitimement échouer ici —
        // backend absent — mais jamais avec un 429).
        for (int i = 0; i < 3; i++) {
            webTestClient.post().uri(LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(BODY)
                    .exchange()
                    .expectStatus().value(status ->
                            assertThat(status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));
        }

        // 4e requête (même IP) : rejetée par le Gateway, corps générique.
        webTestClient.post().uri(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After")
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("Trop de tentatives de connexion");
                    assertThat(body).doesNotContain("password");
                });
    }
}
