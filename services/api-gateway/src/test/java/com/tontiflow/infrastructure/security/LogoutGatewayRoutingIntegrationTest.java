package com.tontiflow.infrastructure.security;

import com.sun.net.httpserver.HttpServer;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preuve du passage REEL de {@code POST /api/v1/auth/logout} a travers le Gateway
 * (TICKET-4, constat F-1) vers un service aval. {@code authentication-service} n'est
 * pas demarre dans ce module : un petit serveur HTTP JDK de test joue son role et
 * enregistre exactement ce qu'il recoit (methode, chemin, corps, en-tete
 * Authorization).
 *
 * <p>Demontre : logout sans Access Token, avec Access Token expire et avec Access
 * Token valide atteignent l'aval (jamais bloques par le Gateway ni par son filtre
 * JWT) ; le corps {@code RefreshTokenRequest} est transmis ; la reponse aval est
 * propagee ; les methodes autres que POST restent refusees par le Gateway et
 * n'atteignent JAMAIS l'aval. La revocation effective de la famille cote
 * {@code authentication-service} est prouvee separement (RefreshTokenIntegrationTest).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GatewayJwtTestSecurityConfiguration.class)
class LogoutGatewayRoutingIntegrationTest {

    private record Received(String method, String path, String authorization, String body) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer DOWNSTREAM = startDownstream();

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECEIVED.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body));
                byte[] payload = "{\"downstream\":\"reached\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void downstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("AUTHENTICATION_SERVICE_URL", () -> "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort());
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop(0);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KeyPair gatewayJwtTestKeyPair;

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    private static final String BODY = "{\"refreshToken\":\"opaque-refresh-token-value\"}";

    private void assertLogoutReachedDownstream(String expectedAuthorization) {
        assertThat(RECEIVED).hasSize(1);
        Received received = RECEIVED.get(0);
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.path()).isEqualTo("/api/v1/auth/logout");
        assertThat(received.body()).isEqualTo(BODY);
        assertThat(received.authorization()).isEqualTo(expectedAuthorization);
    }

    @Test
    void logout_withoutAccessToken_reachesDownstream_andResponseIsPropagated() {
        webTestClient.post().uri("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"downstream\":\"reached\"}");

        assertLogoutReachedDownstream(null);
    }

    @Test
    void logout_withExpiredAccessToken_reachesDownstream() {
        String expired = "Bearer " + buildToken(Instant.now().minus(1, ChronoUnit.MINUTES));

        webTestClient.post().uri("/api/v1/auth/logout")
                .header("Authorization", expired)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isOk();

        assertLogoutReachedDownstream(expired);
    }

    @Test
    void logout_withValidAccessToken_stillReachesDownstream_existingBehaviourPreserved() {
        String valid = "Bearer " + buildToken(Instant.now().plus(15, ChronoUnit.MINUTES));

        webTestClient.post().uri("/api/v1/auth/logout")
                .header("Authorization", valid)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus().isOk();

        assertLogoutReachedDownstream(valid);
    }

    @Test
    void logout_withOtherMethods_isRefusedByGateway_andNeverReachesDownstream() {
        webTestClient.get().uri("/api/v1/auth/logout").exchange().expectStatus().isUnauthorized();
        webTestClient.put().uri("/api/v1/auth/logout").exchange().expectStatus().isUnauthorized();
        webTestClient.delete().uri("/api/v1/auth/logout").exchange().expectStatus().isUnauthorized();

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void otherAuthRoute_withoutToken_isRefusedByGateway_andNeverReachesDownstream() {
        webTestClient.post().uri("/api/v1/auth/sessions").exchange().expectStatus().isUnauthorized();

        assertThat(RECEIVED).isEmpty();
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
                .claim(JwtClaimNames.PERMISSIONS, List.of())
                .signWith(gatewayJwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
