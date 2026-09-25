package com.tontiflow.infrastructure.ratelimit;

import com.tontiflow.infrastructure.security.GatewayJwtTestSecurityConfiguration;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TICKET-5 (constat F-7) - preuve de bout en bout, sur le Gateway reel (chaine Spring
 * Security + routage + filtres globaux), que les variantes percent-encodees d'un endpoint
 * consomment le MEME quota que le chemin canonique, pour les six rate limiters.
 *
 * <p>Avant la correction, {@code /api/v1/auth/logou%74} etait public pour Spring Security
 * (chemin decode segment par segment) et route vers l'aval, mais echappait au rate limiter
 * qui comparait une regex a {@code getRawPath()} : le quota n'etait jamais atteint.</p>
 *
 * <p>Les URI sont construites via {@link URI#create(String)} avec le port reel : un
 * {@code uri(String)} de {@code WebTestClient} reencoderait {@code %} en {@code %25}. Le
 * seuil est abaisse a 3 pour chaque filtre via {@code properties} (valeurs de production
 * inchangees). Le compteur est par filtre et l'adresse cliente toujours 127.0.0.1 : chaque
 * endpoint n'est donc epuise qu'une seule fois, dans une seule methode de test.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "auth-ip-rate-limit.login-per-minute=3",
                "register-ip-rate-limit.register-per-minute=3",
                "refresh-ip-rate-limit.refresh-per-minute=3",
                "logout-ip-rate-limit.logout-per-minute=3",
                "admin-ip-rate-limit.admin-per-minute=3",
                "claim-ip-rate-limit.ip-per-minute=3",
                "auth-ip-rate-limit.trusted-proxy-count=0",
                "register-ip-rate-limit.trusted-proxy-count=0",
                "refresh-ip-rate-limit.trusted-proxy-count=0",
                "logout-ip-rate-limit.trusted-proxy-count=0",
                "admin-ip-rate-limit.trusted-proxy-count=0",
                "claim-ip-rate-limit.trusted-proxy-count=0"
        })
@ActiveProfiles("test")
@Import(GatewayJwtTestSecurityConfiguration.class)
class EncodedPathRateLimitIntegrationTest {

    private static final int LIMIT = 3;
    private static final String BODY = "{\"x\":\"y\"}";

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KeyPair gatewayJwtTestKeyPair;

    private WebTestClient.ResponseSpec call(HttpMethod method, String rawPath, String bearer) {
        URI uri = URI.create("http://localhost:" + port + rawPath);
        assertThat(uri.getRawPath()).isEqualTo(rawPath);
        WebTestClient.RequestBodySpec spec = webTestClient.method(method).uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec.header("Authorization", "Bearer " + bearer);
        }
        return spec.bodyValue(BODY).exchange();
    }

    private int statusOf(HttpMethod method, String rawPath, String bearer) {
        return call(method, rawPath, bearer).returnResult(String.class).getStatus().value();
    }

    /**
     * Consomme le quota en alternant canonique et variantes encodees, verifie a chaque etape
     * que Spring Security reconnait le chemin (ni 401, ni 429 sous le seuil : parite
     * Security / rate limit), puis que canonique ET variantes sont refusees en 429.
     */
    private void assertSharedQuota(HttpMethod method, String canonical, List<String> variants, String bearer) {
        for (int i = 0; i < LIMIT; i++) {
            String path = i == 0 ? canonical : variants.get((i - 1) % variants.size());
            int status = statusOf(method, path, bearer);
            assertThat(status).as("sous le seuil : %s", path)
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        call(method, canonical, bearer).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After");
        for (String variant : variants) {
            call(method, variant, bearer).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectHeader().exists("Retry-After");
        }
    }

    @Test
    void login_encodedVariantsShareTheCanonicalQuota() {
        assertSharedQuota(HttpMethod.POST, "/api/v1/auth/login",
                List.of("/api/v1/auth/logi%6E", "/api/v1/auth/%6Cogin"), null);
    }

    @Test
    void register_encodedVariantsShareTheCanonicalQuota() {
        assertSharedQuota(HttpMethod.POST, "/api/v1/auth/register",
                List.of("/api/v1/auth/regist%65r", "/api/v1/auth/%72egister"), null);
    }

    @Test
    void refresh_encodedVariantsShareTheCanonicalQuota() {
        assertSharedQuota(HttpMethod.POST, "/api/v1/auth/refresh",
                List.of("/api/v1/auth/refres%68", "/api/v1/auth/%72efresh"), null);
    }

    @Test
    void logout_encodedVariantsShareTheCanonicalQuota() {
        assertSharedQuota(HttpMethod.POST, "/api/v1/auth/logout",
                List.of("/api/v1/auth/logou%74", "/api/v1/auth/%6Cogout"), null);
    }

    @Test
    void admin_encodedVariantsShareTheCanonicalQuota_acrossMethods() {
        String token = buildToken();
        assertSharedQuota(HttpMethod.GET, "/api/v1/admin/roles",
                List.of("/api/v1/adm%69n/roles", "/api/v1/%61dmin/%72oles"), token);
        // Meme quota pour la racine et le slash final, toutes methodes confondues.
        call(HttpMethod.DELETE, "/api/v1/adm%69n", token).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        call(HttpMethod.GET, "/api/v1/admin/", token).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void claim_encodedVariantsAndEncodedIdShareTheCanonicalQuota() {
        assertSharedQuota(HttpMethod.POST, "/api/v1/tontines/abc/members/claim",
                List.of("/api/v1/tontines/abc/members/%63laim", "/api/v1/tontines/a%62c/members/clai%6D"), buildToken());
        // Slash final : meme compteur (contrat historique du filtre).
        call(HttpMethod.POST, "/api/v1/tontines/abc/members/claim/", buildToken())
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void neighborRoutes_areNeverLimited_evenWhenEncoded() {
        String token = buildToken();
        // /administration : aucune route Gateway, jamais le quota admin ; /logout-all : protege, jamais public.
        for (int i = 0; i < LIMIT + 3; i++) {
            assertThat(statusOf(HttpMethod.GET, "/api/v1/adm%69nistration/x", token))
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/logou%74-all", null))
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(statusOf(HttpMethod.POST, "/api/v1/tontines/abc/members/clai%6Dx", token))
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    /**
     * Le pare-feu de Spring Security ({@code StrictServerWebExchangeFirewall}, dans
     * {@code WebFilterChainProxy}) rejette ces variantes AVANT les filtres globaux : le filtre
     * de rate limit ne les reproduit pas. Ce test verrouille simplement que le comportement
     * observable du Gateway n'a pas change avec TICKET-5 (aucune ne devient acceptee).
     */
    @Test
    void variantsRejectedByTheFirewall_remainRejected_withBadRequest() {
        for (String path : List.of(
                "/api/v1/auth/logout;x=1",
                "/api/v1/auth/login;x=1",
                "/api/v1/auth%2Flogout",
                "/api/v1/auth/logout%2F",
                "/api/v1/auth/logout%3Bx=1",
                "/api/v1/auth/logou%2574",
                "/api/v1/auth//logout",
                "/api/v1/auth/logout%00",
                "/api/v1/auth/%2e%2e/auth/logout")) {
            assertThat(statusOf(HttpMethod.POST, path, null)).as(path).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }
    }

    @Test
    void trailingSlashOnPublicAuthRoutes_remainsProtected_asBefore() {
        // Politique de securite inchangee : seul le chemin exact est public au Gateway.
        assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/logout/", null)).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/logou%74/", null)).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private String buildToken() {
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
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
