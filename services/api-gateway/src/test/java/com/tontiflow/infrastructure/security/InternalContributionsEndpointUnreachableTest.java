package com.tontiflow.infrastructure.security;

import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Régression Gateway (décision R5, §12/§13) : automatise la partie du
 * scénario prouvé manuellement en Phase R4 qui ne dépend d'aucun service en
 * aval — {@code /internal/contributions} (financial-service, décision R3)
 * ne correspond à <b>aucun</b> prédicat de route déclaré (seul {@code
 * /api/v1/financials/**} est routé), donc la Gateway le rejette elle-même
 * ({@code 404}) avant toute tentative de proxy, quel que soit l'état de
 * {@code financial-service}.
 *
 * <p><b>Découverte réelle pendant l'écriture de ce test</b> (pas un bug
 * applicatif — une correction de l'hypothèse initiale du test) : la chaîne
 * {@code SecurityWebFilterChain} de la Gateway ({@code
 * anyExchange().authenticated()}, fail-closed) s'exécute <i>avant</i> la
 * décision de routage — une requête non authentifiée sur un chemin sans
 * route reçoit {@code 401}, pas {@code 404}. La preuve véritable que le
 * chemin n'est routé nulle part nécessite donc un appelant réellement
 * authentifié (JWT valide, même patron que {@code SecurityConfigIntegrationTest}).</p>
 *
 * <p><b>Limite explicitement documentée</b> (§13) : la variante {@code
 * /api/v1/financials/internal/contributions} (qui correspond bien au
 * prédicat {@code financial-service} et est donc réellement transmise, pour
 * y être ensuite rejetée par {@code financial-service} lui-même faute de
 * route correspondante côté serveur — prouvé réellement en Phase R4) n'est
 * pas automatisée ici : la reproduire fidèlement nécessiterait de démarrer
 * {@code financial-service} en plus de la Gateway, une infrastructure de
 * test disproportionnée pour ce seul scénario de régression (§13 autorise
 * explicitement à ne pas l'ajouter dans ce cas et à documenter la limite).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GatewayJwtTestSecurityConfiguration.class)
class InternalContributionsEndpointUnreachableTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KeyPair gatewayJwtTestKeyPair;

    @Test
    void internalContributionsEndpoint_withoutToken_isRejectedByGatewaySecurity_beforeAnyRoutingDecision() {
        webTestClient.post().uri("/internal/contributions")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void internalContributionsEndpoint_withValidToken_isStillNotRouted_provingNoPredicateMatches() {
        // Preuve reelle qu'aucune route ne matche : meme un appelant authentifie
        // legitime (JWT reellement valide) recoit 404, pas un proxy vers un backend.
        String validToken = buildToken(Instant.now().plus(15, ChronoUnit.MINUTES));

        webTestClient.post().uri("/internal/contributions")
                .header("Authorization", "Bearer " + validToken)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String buildToken(Instant expiresAt) {
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(expiresAt))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "creator-r5")
                .claim(JwtClaimNames.EMAIL, "creator-r5@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_USER"))
                .claim(JwtClaimNames.PERMISSIONS, List.of())
                .signWith(gatewayJwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
