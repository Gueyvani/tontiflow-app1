package com.tontiflow.interfaces.rest;

import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.model.TontineMember;
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
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration réel de {@link TontineController} : démarrage Spring
 * complet, H2 réel, authentification JWT réelle.
 *
 * <p>{@code POST /api/v1/tontines} porte désormais les champs obligatoires
 * de {@code TontineConfig} (décision métier validée : création
 * transactionnelle Tontine+Config) — {@link #validCreateTontineJson}
 * fournit un corps de requête valide réutilisable par tous les tests qui
 * n'exercent pas spécifiquement la validation de ces champs.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class TontineControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KeyPair jwtTestKeyPair;

    @Autowired
    private TontineRepository tontineRepository;

    @Autowired
    private TontineMemberRepository memberRepository;

    @Test
    void createTontine_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/tontines", jsonEntity(validCreateTontineJson("Test")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createTontine_withBlankName_isRejectedWithBadRequest() {
        UUID creator = UUID.randomUUID();
        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines", validCreateTontineJson(""), creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createTontine_withValidToken_persistsWithCreatorFromJwt() {
        UUID creator = UUID.randomUUID();

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines", validCreateTontineJson("Tontine test"), creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"name\":\"Tontine test\"");
        assertThat(response.getBody()).contains(creator.toString());
    }

    @Test
    void createTontine_withMissingContributionAmount_returns400() {
        UUID creator = UUID.randomUUID();
        String json = "{\"name\":\"Tontine\",\"contributionFrequency\":\"MONTHLY\",\"maxMembers\":10,"
                + "\"rotationType\":\"SEQUENTIAL\",\"nonCompliantBehavior\":\"POSTPONE\"}";

        ResponseEntity<String> response = exchangeWithBearer(HttpMethod.POST, "/api/v1/tontines", json, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createTontine_withInvalidContributionFrequency_returns400() {
        UUID creator = UUID.randomUUID();
        String json = "{\"name\":\"Tontine\",\"contributionAmount\":100,\"contributionFrequency\":\"YEARLY\","
                + "\"maxMembers\":10,\"rotationType\":\"SEQUENTIAL\",\"nonCompliantBehavior\":\"POSTPONE\"}";

        ResponseEntity<String> response = exchangeWithBearer(HttpMethod.POST, "/api/v1/tontines", json, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listTontines_returnsOnlyCallersOwnTontines() {
        UUID creator = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Long ownTontineId = createTontineAndGetId(creator);
        createTontineAndGetId(otherUser); // tontine d'un autre createur, ne doit pas apparaitre

        ResponseEntity<String> response = exchangeWithBearer(HttpMethod.GET, "/api/v1/tontines", null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":" + ownTontineId);
        assertThat(response.getBody()).contains(creator.toString());
        assertThat(response.getBody()).doesNotContain(otherUser.toString());
    }

    @Test
    void listTontines_whenNoneOwned_returnsEmptyList() {
        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines", null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void listTontines_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/tontines", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getTontine_forUnknownId_returnsNotFound() {
        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/999999", null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTontine_forUnknownId_withCorrelationIdHeader_echoesCorrelationId() {
        String correlationId = "test-correlation-id-42";

        ResponseEntity<String> response = exchangeWithBearerAndCorrelationId(
                HttpMethod.GET, "/api/v1/tontines/999999", UUID.randomUUID(), correlationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"correlationId\":\"" + correlationId + "\"");
    }

    @Test
    void getTontine_forUnknownId_withBlankCorrelationIdHeader_generatesFallbackCorrelationId() {
        ResponseEntity<String> response = exchangeWithBearerAndCorrelationId(
                HttpMethod.GET, "/api/v1/tontines/999999", UUID.randomUUID(), "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String body = response.getBody();
        assertThat(body).doesNotContain("\"correlationId\":\"   \"");
        assertThat(extractCorrelationId(body)).isNotBlank();
    }

    @Test
    void getTontine_forExistingTontine_returnsOkWithData() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId, null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"name\":\"Tontine\"");
        assertThat(response.getBody()).contains(creator.toString());
    }

    @Test
    void getTontine_asNonMember_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId, null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listMembers_asNonMember_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/members", null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void addMember_asNonMember_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/members",
                "{\"userId\":99,\"sequentialOrder\":1}", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Aucun membre ne doit avoir ete ajoute malgre la tentative refusee.
        ResponseEntity<String> membersAfter = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/members", null, creator);
        assertThat(membersAfter.getBody()).doesNotContain("\"userId\":99");
    }

    @Test
    void addMember_thenDuplicateIsRejectedWithConflict() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> first = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/members",
                "{\"userId\":42,\"sequentialOrder\":1}", creator);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/members",
                "{\"userId\":42,\"sequentialOrder\":2}", creator);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listMembers_returnsAddedMember() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        exchangeWithBearer(HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/members",
                "{\"userId\":7,\"sequentialOrder\":1}", creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/members", null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"userId\":7");
    }

    @Test
    void addMember_createsMemberAsPendingWithoutAccountId() {
        // Décision R18 D1 : un membre ajouté via l'API n'est pas encore lié à
        // un compte TontiFlow -> PENDING, accountId null.
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        exchangeWithBearer(HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/members",
                "{\"userId\":7,\"sequentialOrder\":1}", creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/members", null, creator);
        assertThat(response.getBody()).contains("\"status\":\"PENDING\"").contains("\"accountId\":null");

        TontineMember persisted = memberRepository.findByTontineId(tontineId).get(0);
        assertThat(persisted.getStatus()).isEqualTo(MemberStatus.PENDING);
        assertThat(persisted.getAccountId()).isNull();
    }

    @Test
    void addMember_withOptionalContact_persistsAndEchoesIt_withoutBreakingLegacyCalls() {
        // R20-B : displayName / invitedPhone sont OPTIONNELS. Un ancien appel
        // sans ces champs continue de fonctionner (cf. test ci-dessus) ; un
        // appel qui les fournit les persiste et les renvoie.
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> add = exchangeWithBearer(HttpMethod.POST,
                "/api/v1/tontines/" + tontineId + "/members",
                "{\"userId\":7,\"sequentialOrder\":1,\"displayName\":\"Ahmed D.\",\"invitedPhone\":\"+22170000000\"}",
                creator);
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add.getBody()).contains("\"displayName\":\"Ahmed D.\"").contains("\"invitedPhone\":\"+22170000000\"");

        TontineMember persisted = memberRepository.findByTontineId(tontineId).get(0);
        assertThat(persisted.getDisplayName()).isEqualTo("Ahmed D.");
        assertThat(persisted.getInvitedPhone()).isEqualTo("+22170000000");
        assertThat(persisted.getStatus()).isEqualTo(MemberStatus.PENDING);
    }

    @Test
    void activeMemberWithAccountId_persistsAndIsReadBack() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        UUID account = UUID.randomUUID();

        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(7L);
        member.setSequentialOrder(1);
        member.setStatus(MemberStatus.ACTIVE);
        member.setAccountId(account);
        Long id = memberRepository.save(member).getId();

        TontineMember reloaded = memberRepository.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(reloaded.getAccountId()).isEqualTo(account);
    }

    @Test
    void getConfig_asCreator_returnsConfig() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/config", null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"contributionFrequency\":\"MONTHLY\"");
        assertThat(response.getBody()).contains("\"maxMembers\":10");
    }

    @Test
    void getConfig_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/config", null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateConfig_asCreator_returns200WithUpdatedFields() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        String updateJson = "{\"contributionAmount\":500,\"contributionFrequency\":\"WEEKLY\",\"maxMembers\":20,"
                + "\"rotationType\":\"RANDOM\",\"nonCompliantBehavior\":\"ALLOW\",\"reorganisationAllowed\":false}";

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/" + tontineId + "/config", updateJson, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"contributionFrequency\":\"WEEKLY\"");
        assertThat(response.getBody()).contains("\"maxMembers\":20");
        assertThat(response.getBody()).contains("\"reorganisationAllowed\":false");
    }

    @Test
    void updateConfig_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        String updateJson = "{\"contributionAmount\":500,\"contributionFrequency\":\"WEEKLY\",\"maxMembers\":20,"
                + "\"rotationType\":\"RANDOM\",\"nonCompliantBehavior\":\"ALLOW\"}";

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/" + tontineId + "/config", updateJson, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateConfig_reducingMaxMembersBelowMemberCount_returns409() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        exchangeWithBearer(HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/members",
                "{\"userId\":1,\"sequentialOrder\":1}", creator);
        exchangeWithBearer(HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/members",
                "{\"userId\":2,\"sequentialOrder\":2}", creator);
        String updateJson = "{\"contributionAmount\":500,\"contributionFrequency\":\"WEEKLY\",\"maxMembers\":1,"
                + "\"rotationType\":\"RANDOM\",\"nonCompliantBehavior\":\"ALLOW\"}";

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/" + tontineId + "/config", updateJson, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private Long createTontineAndGetId(UUID creator) {
        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines", validCreateTontineJson("Tontine"), creator);
        String body = response.getBody();
        String idStr = body.substring(body.indexOf("\"id\":") + 5, body.indexOf(",", body.indexOf("\"id\":")));
        return Long.parseLong(idStr.trim());
    }

    /**
     * Corps de requête {@code POST /api/v1/tontines} valide, avec tous les
     * champs de configuration obligatoires renseignés (décision métier
     * validée) ; {@code reorganisationAllowed} volontairement omis pour
     * exercer son défaut ({@code true}).
     */
    private static String validCreateTontineJson(String name) {
        return "{\"name\":\"" + name + "\",\"contributionAmount\":100,\"contributionFrequency\":\"MONTHLY\","
                + "\"maxMembers\":10,\"rotationType\":\"SEQUENTIAL\",\"nonCompliantBehavior\":\"POSTPONE\"}";
    }

    private ResponseEntity<String> exchangeWithBearer(HttpMethod method, String path, String body, UUID subject) {
        String token = validToken(subject);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(path, method, entity, String.class);
    }

    private ResponseEntity<String> exchangeWithBearerAndCorrelationId(
            HttpMethod method, String path, UUID subject, String correlationId) {
        String token = validToken(subject);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", correlationId);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        return restTemplate.exchange(path, method, entity, String.class);
    }

    private static String extractCorrelationId(String body) {
        String marker = "\"correlationId\":\"";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String validToken(UUID subject) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, subject.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(now))
                .claim(JwtClaimNames.EXPIRATION, Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(Set.of("ROLE_USER")))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(Set.<String>of()))
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
