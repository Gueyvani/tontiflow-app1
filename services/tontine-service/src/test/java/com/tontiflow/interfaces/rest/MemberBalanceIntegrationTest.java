package com.tontiflow.interfaces.rest;

import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.client.AccountBalanceResponse;
import com.tontiflow.infrastructure.client.FinancialServiceClient;
import com.tontiflow.infrastructure.client.LedgerLineResponse;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test d'intégration réel du flux de consultation du solde d'un membre
 * (décision R8) — symétrique à {@code BalanceIntegrationTest} (décision
 * R7). {@link FinancialServiceClient} mocké : ce test prouve
 * l'autorisation et la revalidation d'appartenance côté tontine-service ;
 * le comportement réel du compte MEMBER est déjà prouvé générique côté
 * financial-service ({@code AccountBalanceControllerIntegrationTest},
 * décision R7 — aucune modification financial-service en R8).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class MemberBalanceIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KeyPair jwtTestKeyPair;
    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineMemberRepository memberRepository;
    @MockBean
    private FinancialServiceClient financialServiceClient;

    @Test
    void getMemberBalance_asCreator_withMemberBelongingToTontine_returnsBalance() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long memberId = createMemberAndGetId(tontineId);
        when(financialServiceClient.getMemberBalance(eq(memberId), anyString()))
                .thenReturn(new AccountBalanceResponse("MRU", new BigDecimal("300.00")));

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/members/" + memberId + "/balance", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("300.00").contains("MRU");
        verify(financialServiceClient).getMemberBalance(eq(memberId), anyString());
    }

    @Test
    void getMemberBalance_asNonCreator_isForbidden_andFinancialServiceNeverCalled() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long memberId = createMemberAndGetId(tontineId);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/members/" + memberId + "/balance", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(financialServiceClient, never()).getMemberBalance(any(), anyString());
    }

    @Test
    void getMemberBalance_withMemberFromAnotherTontine_isNotFound_andFinancialServiceNeverCalled() {
        UUID creatorA = UUID.randomUUID();
        Long tontineA = createTontineAndGetId(creatorA);

        UUID creatorB = UUID.randomUUID();
        Long tontineB = createTontineAndGetId(creatorB);
        Long memberOfB = createMemberAndGetId(tontineB);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineA + "/members/" + memberOfB + "/balance", creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).getMemberBalance(any(), anyString());
    }

    @Test
    void getMemberBalance_withUnknownMember_isNotFound() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/members/999999/balance", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).getMemberBalance(any(), anyString());
    }

    @Test
    void getMemberBalance_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/tontines/1/members/1/balance", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(financialServiceClient, never()).getMemberBalance(any(), anyString());
    }

    @Test
    void getMemberStatement_asCreator_withMemberBelongingToTontine_returnsLines() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long memberId = createMemberAndGetId(tontineId);
        when(financialServiceClient.getMemberStatement(eq(memberId), anyString())).thenReturn(List.of(
                new LedgerLineResponse("DISBURSEMENT_RECORDED", "Versement round 1 tontine " + tontineId,
                        BigDecimal.ZERO, new BigDecimal("300.00"), "MRU", Instant.now())));

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/members/" + memberId + "/statement", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("DISBURSEMENT_RECORDED").contains("300.00");
        verify(financialServiceClient).getMemberStatement(eq(memberId), anyString());
    }

    @Test
    void getMemberStatement_asNonCreator_isForbidden_andFinancialServiceNeverCalled() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long memberId = createMemberAndGetId(tontineId);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/members/" + memberId + "/statement", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(financialServiceClient, never()).getMemberStatement(any(), anyString());
    }

    @Test
    void getMemberStatement_withMemberFromAnotherTontine_isNotFound_andFinancialServiceNeverCalled() {
        UUID creatorA = UUID.randomUUID();
        Long tontineA = createTontineAndGetId(creatorA);

        UUID creatorB = UUID.randomUUID();
        Long tontineB = createTontineAndGetId(creatorB);
        Long memberOfB = createMemberAndGetId(tontineB);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineA + "/members/" + memberOfB + "/statement", creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).getMemberStatement(any(), anyString());
    }

    @Test
    void getMemberStatement_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/tontines/1/members/1/statement", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(financialServiceClient, never()).getMemberStatement(any(), anyString());
    }

    private Long createTontineAndGetId(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Tontine de test R8");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private Long createMemberAndGetId(Long tontineId) {
        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(555L);
        member.setSequentialOrder(1);
        return memberRepository.save(member).getId();
    }

    private ResponseEntity<String> exchangeWithBearer(String path, UUID subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validToken(subject));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(path, HttpMethod.GET, entity, String.class);
    }

    private String validToken(UUID subject) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, subject.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(now))
                .claim(JwtClaimNames.EXPIRATION, Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "creator")
                .claim(JwtClaimNames.EMAIL, "creator@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(Set.of("ROLE_USER")))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(Set.<String>of()))
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
