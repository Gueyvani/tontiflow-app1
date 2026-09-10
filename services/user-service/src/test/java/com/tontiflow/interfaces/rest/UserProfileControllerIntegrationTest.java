package com.tontiflow.interfaces.rest;

import com.tontiflow.infrastructure.repository.UserProfileRepository;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
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
import org.springframework.http.MediaType;
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
 * Test d'intégration réel de {@link UserProfileController} : démarrage
 * Spring complet, H2 réel, authentification JWT réelle — même patron que
 * {@code TontineControllerIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class UserProfileControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KeyPair jwtTestKeyPair;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Test
    void getMe_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/users/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getMe_whenNoProfileExists_returnsNotFound() {
        UUID userId = UUID.randomUUID();

        ResponseEntity<String> response = exchangeWithBearer(HttpMethod.GET, "/api/v1/users/me", null, userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void putMe_withValidBody_createsProfileAndIsRetrievableByGet() {
        UUID userId = UUID.randomUUID();
        String requestBody = "{\"fullName\":\"Alice Diop\",\"phoneNumber\":\"+221771234567\"}";

        ResponseEntity<String> putResponse = exchangeWithBearer(HttpMethod.PUT, "/api/v1/users/me", requestBody, userId);

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putResponse.getBody()).contains("Alice Diop").contains("+221771234567");
        assertThat(userProfileRepository.findById(userId)).isPresent();

        ResponseEntity<String> getResponse = exchangeWithBearer(HttpMethod.GET, "/api/v1/users/me", null, userId);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).contains("Alice Diop").contains("+221771234567");
    }

    @Test
    void putMe_calledTwice_replacesProfileRatherThanDuplicating() {
        UUID userId = UUID.randomUUID();
        exchangeWithBearer(HttpMethod.PUT, "/api/v1/users/me",
                "{\"fullName\":\"Alice Diop\",\"phoneNumber\":\"+221771234567\"}", userId);

        ResponseEntity<String> secondPut = exchangeWithBearer(HttpMethod.PUT, "/api/v1/users/me",
                "{\"fullName\":\"Alice Updated\",\"phoneNumber\":null}", userId);

        assertThat(secondPut.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondPut.getBody()).contains("Alice Updated");
        assertThat(userProfileRepository.findById(userId).orElseThrow().getFullName()).isEqualTo("Alice Updated");
        assertThat(userProfileRepository.findById(userId).orElseThrow().getPhoneNumber()).isNull();
    }

    @Test
    void putMe_withBlankFullName_isRejectedWithBadRequest() {
        UUID userId = UUID.randomUUID();

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/users/me", "{\"fullName\":\"\"}", userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getMe_isScopedToCallingUser_neverReturnsAnotherUsersProfile() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        exchangeWithBearer(HttpMethod.PUT, "/api/v1/users/me",
                "{\"fullName\":\"User A\"}", userA);

        ResponseEntity<String> response = exchangeWithBearer(HttpMethod.GET, "/api/v1/users/me", null, userB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> exchangeWithBearer(HttpMethod method, String path, String body, UUID subject) {
        String token = validToken(subject);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(path, method, entity, String.class);
    }

    private String validToken(UUID subject) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, subject.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(now))
                .claim(JwtClaimNames.EXPIRATION, Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice@tontiflow.test")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(Set.of("ROLE_USER")))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(Set.<String>of()))
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
