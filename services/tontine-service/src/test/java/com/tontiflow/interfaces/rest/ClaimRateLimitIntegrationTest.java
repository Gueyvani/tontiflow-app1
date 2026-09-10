package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.MemberInvitationService;
import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prouve que le rate limiting par compte (R21-B / D4, dimension IP déplacée
 * vers api-gateway en R21-C.A3.2) rejette en 429 <b>avant</b> d'atteindre le
 * contrôleur : {@link MemberInvitationService#claim} n'est jamais invoqué pour
 * les requêtes rejetées (donc aucun accès repository / PostgreSQL). Seuil
 * compte volontairement bas via {@code @SpringBootTest properties}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"claim-rate-limit.account-per-minute=3"})
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class ClaimRateLimitIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KeyPair jwtTestKeyPair;
    @MockBean
    private MemberInvitationService memberInvitationService;

    @Test
    void claim_isRateLimited_after429ServiceIsNotInvoked() {
        UUID account = UUID.randomUUID();
        TontineMember activated = new TontineMember();
        activated.setTontineId(1L);
        activated.setStatus(MemberStatus.ACTIVE);
        activated.setAccountId(account);
        when(memberInvitationService.claim(anyLong(), anyString(), any(UUID.class))).thenReturn(activated);

        String path = "/api/v1/tontines/1/members/claim";
        String body = "{\"code\":\"ABCDEFGH\"}";

        // 3 premières requêtes : sous le seuil → atteignent le contrôleur.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> ok = post(path, body, account);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // 4e requête (même compte) : rejetée par le filtre.
        ResponseEntity<String> limited = post(path, body, account);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(limited.getBody()).contains("Trop de tentatives");
        assertThat(limited.getBody()).doesNotContain("Invitation").doesNotContain("code");

        // Le service (et donc le repository / PostgreSQL) n'a été appelé que
        // pour les 3 requêtes autorisées — jamais pour le 429.
        verify(memberInvitationService, times(3)).claim(anyLong(), anyString(), any(UUID.class));
    }

    private ResponseEntity<String> post(String path, String body, UUID subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(validToken(subject));
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String validToken(UUID subject) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, subject.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(now))
                .claim(JwtClaimNames.EXPIRATION, Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "u@t.test")
                .claim(JwtClaimNames.EMAIL, "u@t.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(Set.of("ROLE_USER")))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(Set.<String>of()))
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
