package com.tontiflow.infrastructure.security;

import com.tontiflow.security.jwt.JwtClaimNames;
import com.tontiflow.security.jwt.ServiceTokenCodec;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration de la chaîne de sécurité de {@code financial-service} (décision F-8) : les
 * endpoints {@code /internal/**} n'acceptent que le jeton de service émis par {@code tontine-service}
 * (HS256, portée par méthode), jamais un JWT utilisateur.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ServiceTokenTestConfiguration.class)
class SecurityConfigIntegrationTest {

    private static final String READ_PATH = "/internal/accounts/1/TONTINE/balance";
    private static final String WRITE_PATH = "/internal/contributions";
    private static final String VALID_WRITE_BODY =
            "{\"tontineId\":9001,\"roundId\":9002,\"memberId\":9003,\"amount\":10.00,\"currency\":\"MRU\"}";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ServiceTokenCodec serviceTokenCodec;

    // --- Décision R11 : /actuator/health public, endpoints sensibles non exposés ---

    @Test
    void health_withoutToken_isPubliclyAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sensitiveActuatorEndpoint_remainsProtectedAndUnexposed() {
        assertThat(restTemplate.getForEntity("/actuator/beans", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Meme avec un jeton de service valide : jamais accessible (le filtre de jeton ne s'applique
        // qu'a /internal/**, donc la requete reste anonyme et anyRequest().denyAll() la refuse).
        assertThat(get("/actuator/beans", ServiceTokenTestConfiguration.readToken(serviceTokenCodec)).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    // --- Jeton de service valide ---

    @Test
    void readEndpoint_withValidReadToken_isAccepted() {
        ResponseEntity<String> response = get(READ_PATH, ServiceTokenTestConfiguration.readToken(serviceTokenCodec));

        assertThat(response.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void writeEndpoint_withValidWriteToken_isAccepted() {
        ResponseEntity<String> response = post(WRITE_PATH, VALID_WRITE_BODY,
                ServiceTokenTestConfiguration.writeToken(serviceTokenCodec));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Absence de jeton et jetons non valides ---

    @Test
    void internalEndpoints_withoutToken_areRejectedWithUnauthorized() {
        assertThat(restTemplate.getForEntity(READ_PATH, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(WRITE_PATH, VALID_WRITE_BODY, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalEndpoints_withUserStyleRs256Jwt_areRejectedWithUnauthorized() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String userJwt = Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_USER", "ROLE_ADMIN"))
                .claim(JwtClaimNames.PERMISSIONS, List.of("SCOPE_ledger.write", "SCOPE_ledger.read"))
                .signWith(pair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThat(get(READ_PATH, userJwt).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(WRITE_PATH, VALID_WRITE_BODY, userJwt).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalEndpoints_withTokenSignedByAnotherSecret_areRejectedWithUnauthorized() {
        ServiceTokenCodec other = new ServiceTokenCodec("another-secret-another-secret-0123456789", Clock.systemUTC());
        String token = ServiceTokenTestConfiguration.writeToken(other);

        assertThat(get(READ_PATH, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(WRITE_PATH, VALID_WRITE_BODY, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalEndpoints_withExpiredToken_areRejectedWithUnauthorized() {
        // Emis dans le passe (2 minutes, au-dela de TTL + tolerance d'horloge).
        Clock past = Clock.fixed(Instant.now().minus(2, ChronoUnit.MINUTES), ZoneOffset.UTC);
        ServiceTokenCodec pastCodec = new ServiceTokenCodec(ServiceTokenTestConfiguration.SECRET, past);
        String token = ServiceTokenTestConfiguration.readToken(pastCodec);

        assertThat(get(READ_PATH, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalEndpoints_withWrongAudienceOrIssuer_areRejectedWithUnauthorized() {
        String wrongAudience = serviceTokenCodec.issue(ServiceTokenCodec.SERVICE_TONTINE, "credit-service",
                List.of(ServiceTokenCodec.SCOPE_LEDGER_READ), null);
        String wrongIssuer = serviceTokenCodec.issue("user-service", ServiceTokenCodec.SERVICE_FINANCIAL,
                List.of(ServiceTokenCodec.SCOPE_LEDGER_READ), null);

        assertThat(get(READ_PATH, wrongAudience).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(READ_PATH, wrongIssuer).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalEndpoints_withMalformedToken_areRejectedWithUnauthorized() {
        assertThat(get(READ_PATH, "ceci-n-est-pas-un-jeton").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalEndpoints_withBasicSchemeOrEmptyBearer_areRejectedWithUnauthorized() {
        HttpHeaders basic = new HttpHeaders();
        basic.set("Authorization", "Basic dXNlcjpwYXNz");
        HttpHeaders empty = new HttpHeaders();
        empty.set("Authorization", "Bearer ");

        assertThat(restTemplate.exchange(READ_PATH, HttpMethod.GET, new HttpEntity<>(basic), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange(READ_PATH, HttpMethod.GET, new HttpEntity<>(empty), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- Portée exigée par méthode (moindre privilège) ---

    @Test
    void writeEndpoint_withReadOnlyToken_isForbidden() {
        String readToken = ServiceTokenTestConfiguration.readToken(serviceTokenCodec);

        assertThat(post(WRITE_PATH, VALID_WRITE_BODY, readToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void readEndpoint_withWriteOnlyToken_isForbidden() {
        String writeToken = ServiceTokenTestConfiguration.writeToken(serviceTokenCodec);

        assertThat(get(READ_PATH, writeToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownInternalPath_withValidToken_isDenied() {
        String token = ServiceTokenTestConfiguration.readToken(serviceTokenCodec);

        assertThat(get("/internal/unknown", token).getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(post("/internal/accounts/1/TONTINE/balance", "{}", token).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.METHOD_NOT_ALLOWED);
    }

    // --- Usurpation : en-têtes de confiance forgés sans effet ---

    @Test
    void forgedTrustHeaders_withoutToken_areRejectedWithUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "utilisateur-admin");
        headers.set("X-Roles", "ROLE_ADMIN");
        headers.set("X-Permissions", "*");

        ResponseEntity<String> response = restTemplate.exchange(
                READ_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
