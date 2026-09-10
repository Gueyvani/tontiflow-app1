package com.tontiflow.interfaces.rest;

import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.model.MemberInvitation;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.repository.MemberInvitationRepository;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration réel de {@link MemberInvitationController} : démarrage
 * Spring complet, H2 réel, JWT réel. Vérifie l'autorisation créateur, le
 * cycle « une seule invitation active », et l'absence totale du code brut
 * hors de la réponse de génération.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class MemberInvitationControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KeyPair jwtTestKeyPair;
    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineMemberRepository memberRepository;
    @Autowired
    private MemberInvitationRepository invitationRepository;

    @Test
    void generateInvitation_asCreator_forPendingMember_returns200WithCodeOnce() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);

        ResponseEntity<String> response = post(
                "/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"memberId\":" + memberId).contains("\"code\":").contains("\"expiresAt\":");

        // Code brut jamais en base : seul le hash SHA-256 hex (64) est stocké.
        List<MemberInvitation> stored = invitationRepository.findByTontineMemberId(memberId);
        assertThat(stored).hasSize(1);
        MemberInvitation inv = stored.get(0);
        assertThat(inv.getCodeHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(inv.getConsumedAt()).isNull();
        assertThat(inv.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(6));
        String rawCode = extract(response.getBody(), "code");
        assertThat(rawCode).hasSize(8);
        assertThat(inv.getCodeHash()).isNotEqualTo(rawCode);
        assertThat(response.getBody()).doesNotContain("codeHash").doesNotContain("consumedAt");
    }

    @Test
    void generateInvitation_withoutToken_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/tontines/1/members/1/invitation", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void generateInvitation_byNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);

        ResponseEntity<String> response = post(
                "/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invitationRepository.findByTontineMemberId(memberId)).isEmpty();
    }

    @Test
    void generateInvitation_forUnknownMember_isNotFound() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);

        ResponseEntity<String> response = post(
                "/api/v1/tontines/" + tontineId + "/members/999999/invitation", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void generateInvitation_forMemberOfAnotherTontine_isNotFound() {
        UUID creatorA = UUID.randomUUID();
        UUID creatorB = UUID.randomUUID();
        Long tontineA = createTontine(creatorA);
        Long tontineB = createTontine(creatorB);
        Long memberOfB = createMember(tontineB, MemberStatus.PENDING);

        ResponseEntity<String> response = post(
                "/api/v1/tontines/" + tontineA + "/members/" + memberOfB + "/invitation", creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void generateInvitation_forActiveMember_isConflict() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.ACTIVE);

        ResponseEntity<String> response = post(
                "/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(invitationRepository.findByTontineMemberId(memberId)).isEmpty();
    }

    @Test
    void generateInvitation_twice_invalidatesPreviousAndKeepsExactlyOneActive() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);
        String path = "/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation";

        String code1 = extract(post(path, creator).getBody(), "code");
        String code2 = extract(post(path, creator).getBody(), "code");

        assertThat(code1).isNotEqualTo(code2);
        List<MemberInvitation> all = invitationRepository.findByTontineMemberId(memberId);
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(i -> i.getConsumedAt() == null).count()).isEqualTo(1);
        assertThat(invitationRepository.findByTontineMemberIdAndConsumedAtIsNull(memberId)).hasSize(1);
    }

    @Test
    void getMembers_neverExposesInvitationSecret() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);
        post("/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", creator);

        ResponseEntity<String> members = exchange(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/members", creator);

        assertThat(members.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(members.getBody()).contains("\"status\":\"PENDING\"");
        assertThat(members.getBody()).doesNotContain("code").doesNotContain("Hash").doesNotContain("invitation");
    }

    @Test
    void generateInvitation_forTwoDifferentMembers_yieldsDistinctHashes() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long m1 = createMember(tontineId, MemberStatus.PENDING);
        Long m2 = createMember(tontineId, MemberStatus.PENDING);

        post("/api/v1/tontines/" + tontineId + "/members/" + m1 + "/invitation", creator);
        post("/api/v1/tontines/" + tontineId + "/members/" + m2 + "/invitation", creator);

        String h1 = invitationRepository.findByTontineMemberId(m1).get(0).getCodeHash();
        String h2 = invitationRepository.findByTontineMemberId(m2).get(0).getCodeHash();
        assertThat(h1).isNotEqualTo(h2);
    }

    // ================= R20-C : claim =================

    @Test
    void claim_withoutToken_isUnauthorized() {
        ResponseEntity<String> r = restTemplate.postForEntity(
                "/api/v1/tontines/1/members/claim", new HttpEntity<>("{\"code\":\"ABCDEFGH\"}", jsonHeaders()),
                String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void claim_withInvalidJwt_isUnauthorized() {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth("not.a.jwt");
        ResponseEntity<String> r = restTemplate.exchange("/api/v1/tontines/1/members/claim",
                HttpMethod.POST, new HttpEntity<>("{\"code\":\"ABCDEFGH\"}", h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void claim_withValidCode_activatesMember_accountIdFromJwt_noSecretLeaked() {
        UUID creator = UUID.randomUUID();
        UUID claimant = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);
        String code = extract(post("/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", creator)
                .getBody(), "code");

        ResponseEntity<String> r = postJson(
                "/api/v1/tontines/" + tontineId + "/members/claim", claimant, "{\"code\":\"" + code + "\"}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"ACTIVE\"").contains("\"accountId\":\"" + claimant + "\"");
        assertThat(r.getBody()).doesNotContain(code).doesNotContain("code").doesNotContain("Hash");

        TontineMember reloaded = memberRepository.findById(memberId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(reloaded.getAccountId()).isEqualTo(claimant);
        assertThat(invitationRepository.findByTontineMemberId(memberId).get(0).getConsumedAt()).isNotNull();
    }

    @Test
    void claim_withUnknownCode_isConflictGeneric() {
        UUID tid = UUID.randomUUID();
        Long tontineId = createTontine(tid);
        ResponseEntity<String> r = postJson(
                "/api/v1/tontines/" + tontineId + "/members/claim", UUID.randomUUID(), "{\"code\":\"ZZZZ2222\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("Invitation invalide.");
    }

    @Test
    void claim_withExpiredInvitation_isConflict_andNotConsumed() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);
        String code = extract(post("/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", creator)
                .getBody(), "code");
        MemberInvitation inv = invitationRepository.findByTontineMemberId(memberId).get(0);
        inv.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        invitationRepository.save(inv);

        ResponseEntity<String> r = postJson(
                "/api/v1/tontines/" + tontineId + "/members/claim", UUID.randomUUID(), "{\"code\":\"" + code + "\"}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(invitationRepository.findByTontineMemberId(memberId).get(0).getConsumedAt()).isNull();
        assertThat(memberRepository.findById(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.PENDING);
    }

    @Test
    void claim_secondTimeSameCode_isConflict_consumedExactlyOnce() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);
        String code = extract(post("/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", creator)
                .getBody(), "code");
        String path = "/api/v1/tontines/" + tontineId + "/members/claim";

        ResponseEntity<String> first = postJson(path, UUID.randomUUID(), "{\"code\":\"" + code + "\"}");
        ResponseEntity<String> second = postJson(path, UUID.randomUUID(), "{\"code\":\"" + code + "\"}");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Le membre n'a été activé qu'une fois (accountId = premier claimant).
        assertThat(memberRepository.findById(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void claim_whenMemberNotPending_isConflict() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);
        String code = extract(post("/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", creator)
                .getBody(), "code");
        // Le membre devient ACTIVE par un autre chemin (simulé) avant le claim.
        TontineMember m = memberRepository.findById(memberId).orElseThrow();
        m.setStatus(MemberStatus.ACTIVE);
        m.setAccountId(UUID.randomUUID());
        memberRepository.save(m);

        ResponseEntity<String> r = postJson(
                "/api/v1/tontines/" + tontineId + "/members/claim", UUID.randomUUID(), "{\"code\":\"" + code + "\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void claim_wrongTontineInPath_isConflict_andNotConsumed() {
        UUID creatorA = UUID.randomUUID();
        UUID creatorB = UUID.randomUUID();
        Long tontineA = createTontine(creatorA);
        Long tontineB = createTontine(creatorB);
        Long memberA = createMember(tontineA, MemberStatus.PENDING);
        String code = extract(post("/api/v1/tontines/" + tontineA + "/members/" + memberA + "/invitation", creatorA)
                .getBody(), "code");

        ResponseEntity<String> r = postJson(
                "/api/v1/tontines/" + tontineB + "/members/claim", UUID.randomUUID(), "{\"code\":\"" + code + "\"}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(invitationRepository.findByTontineMemberId(memberA).get(0).getConsumedAt()).isNull();
    }

    @Test
    void claim_secondMemberSameTontineSameAccount_isConflict_monoParticipation() {
        UUID creator = UUID.randomUUID();
        UUID claimant = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long m1 = createMember(tontineId, MemberStatus.PENDING);
        Long m2 = createMember(tontineId, MemberStatus.PENDING);
        String c1 = extract(post("/api/v1/tontines/" + tontineId + "/members/" + m1 + "/invitation", creator).getBody(), "code");
        String c2 = extract(post("/api/v1/tontines/" + tontineId + "/members/" + m2 + "/invitation", creator).getBody(), "code");
        String path = "/api/v1/tontines/" + tontineId + "/members/claim";

        assertThat(postJson(path, claimant, "{\"code\":\"" + c1 + "\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> second = postJson(path, claimant, "{\"code\":\"" + c2 + "\"}");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(memberRepository.findByTontineId(tontineId).stream()
                .filter(x -> claimant.equals(x.getAccountId())).count()).isEqualTo(1);
        // l'invitation de m2 n'a pas été consommée
        assertThat(invitationRepository.findByTontineMemberId(m2).get(0).getConsumedAt()).isNull();
    }

    @Test
    void claim_sameAccountInAnotherTontine_isAllowed() {
        UUID claimant = UUID.randomUUID();
        Long t1 = createTontine(UUID.randomUUID());
        Long t2 = createTontine(UUID.randomUUID());
        Long m1 = createMember(t1, MemberStatus.PENDING);
        Long m2 = createMember(t2, MemberStatus.PENDING);
        UUID creator1 = tontineRepository.findById(t1).orElseThrow().getCreatorUserId();
        UUID creator2 = tontineRepository.findById(t2).orElseThrow().getCreatorUserId();
        String c1 = extract(post("/api/v1/tontines/" + t1 + "/members/" + m1 + "/invitation", creator1).getBody(), "code");
        String c2 = extract(post("/api/v1/tontines/" + t2 + "/members/" + m2 + "/invitation", creator2).getBody(), "code");

        assertThat(postJson("/api/v1/tontines/" + t1 + "/members/claim", claimant, "{\"code\":\"" + c1 + "\"}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postJson("/api/v1/tontines/" + t2 + "/members/claim", claimant, "{\"code\":\"" + c2 + "\"}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void claim_withBlankCode_isBadRequest() {
        Long tontineId = createTontine(UUID.randomUUID());
        String path = "/api/v1/tontines/" + tontineId + "/members/claim";
        assertThat(postJson(path, UUID.randomUUID(), "{\"code\":\"\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson(path, UUID.randomUUID(), "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void claim_bodyCannotInjectIdentity_accountIdFromJwtOnly() {
        UUID creator = UUID.randomUUID();
        UUID claimant = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, MemberStatus.PENDING);
        String code = extract(post("/api/v1/tontines/" + tontineId + "/members/" + memberId + "/invitation", creator)
                .getBody(), "code");

        ResponseEntity<String> r = postJson("/api/v1/tontines/" + tontineId + "/members/claim", claimant,
                "{\"code\":\"" + code + "\",\"accountId\":\"" + victim + "\",\"userId\":123,\"memberId\":999}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(memberRepository.findById(memberId).orElseThrow().getAccountId()).isEqualTo(claimant);
    }

    // --- helpers ---

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> postJson(String path, UUID subject, String body) {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(validToken(subject));
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private Long createTontine(UUID creator) {
        Tontine t = new Tontine();
        t.setName("R20-B test");
        t.setCreatorUserId(creator);
        t.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(t).getId();
    }

    private Long createMember(Long tontineId, MemberStatus status) {
        TontineMember m = new TontineMember();
        m.setTontineId(tontineId);
        m.setUserId(System.nanoTime());
        m.setSequentialOrder(1);
        m.setStatus(status);
        if (status == MemberStatus.ACTIVE) {
            m.setAccountId(UUID.randomUUID());
        }
        return memberRepository.save(m).getId();
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private ResponseEntity<String> post(String path, UUID subject) {
        return exchange(HttpMethod.POST, path, subject);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, UUID subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validToken(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(null, headers), String.class);
    }

    private String validToken(UUID subject) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, subject.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(now))
                .claim(JwtClaimNames.EXPIRATION, Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "creator@tontiflow.test")
                .claim(JwtClaimNames.EMAIL, "creator@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(Set.of("ROLE_USER")))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(Set.<String>of()))
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
