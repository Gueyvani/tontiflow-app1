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
 * Prouve, de bout en bout dans le Gateway, que {@code POST /api/v1/auth/logout} est
 * limite par IP ({@link LogoutIpRateLimitFilter}, TICKET-4) : sous le seuil, les
 * requetes ne sont jamais rejetees en 429 (le proxy echoue seulement car aucun
 * service aval n'est demarre) ; au-dela, le Gateway repond 429 lui-meme. Le
 * compteur est independant de {@code /refresh} : epuise pour logout, {@code /refresh}
 * n'est pas limite. Seuil volontairement bas via {@code properties}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logout-ip-rate-limit.logout-per-minute=3", "logout-ip-rate-limit.trusted-proxy-count=0"})
@ActiveProfiles("test")
@Import(GatewayJwtTestSecurityConfiguration.class)
class LogoutIpRateLimitIntegrationTest {

    private static final String BODY = "{\"refreshToken\":\"x\"}";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void logout_isRateLimitedByGateway_andRefreshCounterIsIndependent() {
        for (int i = 0; i < 3; i++) {
            webTestClient.post().uri("/api/v1/auth/logout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(BODY)
                    .exchange()
                    .expectStatus().value(status ->
                            assertThat(status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));
        }

        webTestClient.post().uri("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After")
                .expectBody(String.class).value(body ->
                        assertThat(body).contains("Trop de requêtes de déconnexion").doesNotContain("token"));

        // Compteur refresh independant : jamais limite par l'epuisement du quota logout.
        webTestClient.post().uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));
    }
}
