package com.tontiflow.infrastructure.security;

import com.sun.net.httpserver.HttpServer;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
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
 * Décision F-8b : le Gateway ne route plus rien vers {@code financial-service}, {@code credit-service}
 * ni {@code notification-service} (aucun endpoint public derrière ces préfixes). Preuve sur le Gateway
 * réel :
 * <ul>
 *   <li>sans jeton : 401, avant tout routage (chaîne de sécurité, fail-closed) ;</li>
 *   <li>avec un JWT valide : <b>404</b> du Gateway lui-même — si une route existait, le Gateway
 *       tenterait de joindre le service (absent en test) et ne répondrait jamais 404 ;</li>
 *   <li>les variantes de traversée de chemin restent rejetées par le pare-feu (400) ;</li>
 *   <li>les routes conservées restent routées (jamais 404 pour un appelant authentifié).</li>
 * </ul>
 * Les URI sont construites via {@link URI#create(String)} avec le port réel pour ne pas réencoder le
 * chemin.
 *
 * <p>Déterminisme : les services en aval sont remplacés par deux serveurs JDK locaux. Un « piège »
 * reçoit les URL de financial-service, credit-service et notification-service (variables
 * {@code *_SERVICE_URL} d'origine) : avec l'ancienne configuration, la route existerait et le piège
 * répondrait 200 ; il ne doit jamais être atteint. Un second stub reçoit les routes conservées. Aucun
 * test ne dépend donc d'un processus tiers éventuellement à l'écoute sur un port par défaut
 * (8081-8086).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GatewayJwtTestSecurityConfiguration.class)
class RemovedRoutesIntegrationTest {

    private static final List<String> REMOVED_PATHS = List.of(
            "/api/v1/financials/anything",
            "/api/v1/financials/internal/contributions",
            "/api/v1/financials",
            "/api/v1/credits/anything",
            "/api/v1/credits",
            "/api/v1/notifications/anything",
            "/api/v1/notifications");

    private static final List<String> KEPT_PATHS = List.of(
            "/api/v1/users/me",
            "/api/v1/tontines/anything",
            "/api/v1/admin/roles",
            "/api/v1/auth/sessions");

    private static final List<String> TRAP_RECEIVED = new CopyOnWriteArrayList<>();
    private static final List<String> KEPT_RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer TRAP = startStub(TRAP_RECEIVED);
    private static final HttpServer KEPT_DOWNSTREAM = startStub(KEPT_RECEIVED);

    private static HttpServer startStub(List<String> received) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
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
    static void downstreamUrls(DynamicPropertyRegistry registry) {
        String trap = "http://127.0.0.1:" + TRAP.getAddress().getPort();
        String kept = "http://127.0.0.1:" + KEPT_DOWNSTREAM.getAddress().getPort();
        registry.add("FINANCIAL_SERVICE_URL", () -> trap);
        registry.add("CREDIT_SERVICE_URL", () -> trap);
        registry.add("NOTIFICATION_SERVICE_URL", () -> trap);
        registry.add("USER_SERVICE_URL", () -> kept);
        registry.add("TONTINE_SERVICE_URL", () -> kept);
        registry.add("AUTHENTICATION_SERVICE_URL", () -> kept);
    }

    @AfterAll
    static void stopStubs() {
        TRAP.stop(0);
        KEPT_DOWNSTREAM.stop(0);
    }

    @BeforeEach
    void clearReceived() {
        TRAP_RECEIVED.clear();
        KEPT_RECEIVED.clear();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KeyPair gatewayJwtTestKeyPair;

    private int status(HttpMethod method, String rawPath, String bearer) {
        URI uri = URI.create("http://localhost:" + port + rawPath);
        assertThat(uri.getRawPath()).isEqualTo(rawPath);
        WebTestClient.RequestHeadersSpec<?> spec = webTestClient.method(method).uri(uri);
        if (bearer != null) {
            spec = spec.header("Authorization", "Bearer " + bearer);
        }
        return spec.exchange().returnResult(String.class).getStatus().value();
    }

    @Test
    void removedPrefixes_withoutToken_areRejectedWithUnauthorized_beforeAnyRouting() {
        for (String path : REMOVED_PATHS) {
            assertThat(status(HttpMethod.GET, path, null)).as("GET %s", path).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(status(HttpMethod.POST, path, null)).as("POST %s", path).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
        assertThat(TRAP_RECEIVED).isEmpty();
    }

    @Test
    void removedPrefixes_withValidToken_areNotRouted_gatewayAnswersNotFound() {
        String token = validToken();

        for (String path : REMOVED_PATHS) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
                assertThat(status(method, path, token)).as("%s %s", method, path).isEqualTo(HttpStatus.NOT_FOUND.value());
            }
        }
        // Aucune requete n'a jamais ete transmise aux services retires.
        assertThat(TRAP_RECEIVED).isEmpty();
    }

    @Test
    void internalPaths_withValidToken_remainNotRouted() {
        String token = validToken();

        assertThat(status(HttpMethod.POST, "/internal/contributions", token)).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(status(HttpMethod.GET, "/internal/accounts/1/TONTINE/balance", token))
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void pathTraversalTowardsInternal_remainsRejectedByTheFirewall() {
        String token = validToken();

        for (String path : List.of(
                "/api/v1/financials/../../internal/contributions",
                "/api/v1/financials/../internal/contributions",
                "/api/v1/financials/%2e%2e/%2e%2e/internal/contributions",
                "/api/v1/credits/../../actuator/health",
                "/api/v1/notifications/%2e%2e/internal")) {
            assertThat(status(HttpMethod.POST, path, token)).as(path).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }
        assertThat(TRAP_RECEIVED).isEmpty();
    }

    @Test
    void keptRoutes_withValidToken_areStillRouted_toTheirDownstream() {
        String token = validToken();

        for (String path : KEPT_PATHS) {
            assertThat(status(HttpMethod.GET, path, token)).as(path).isEqualTo(HttpStatus.OK.value());
        }

        assertThat(KEPT_RECEIVED).containsExactlyElementsOf(KEPT_PATHS.stream().map(p -> "GET " + p).toList());
        assertThat(TRAP_RECEIVED).isEmpty();
    }

    private String validToken() {
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
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
