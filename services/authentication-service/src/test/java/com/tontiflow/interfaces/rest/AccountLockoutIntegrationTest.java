package com.tontiflow.interfaces.rest;

import com.tontiflow.core.dto.ErrorResponse;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.LoginRequest;
import com.tontiflow.interfaces.rest.dto.RegisterRequest;
import com.tontiflow.interfaces.rest.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prouve, au niveau HTTP, le ralentissement progressif de compte (décision
 * R21-D.5, remplace le verrouillage dur de R21-D.3 — corrige le risque de
 * déni de service par verrouillage, constat D4-01/R21-D.4) déclenché par
 * {@code AuthAccountService.authenticate}.
 *
 * <p>Seuils réels (pas de {@code @SpringBootTest properties} : la table de
 * délai est fixe et non configurable, décision R21-D.5). L'expiration réelle
 * d'un délai (2 à 30 s) n'est pas testée ici par une attente (lenteur/fragilité
 * inutiles) : elle est couverte de façon déterministe par
 * {@code AuthAccountRepositoryTest} (horodatages calculés, pas d'attente
 * réelle). Ce test se concentre sur la preuve décisive de la correction D4-01 :
 * un mot de passe correct réussit <b>immédiatement</b>, sans attendre quoi que
 * ce soit, même juste après de nombreux échecs.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class AccountLockoutIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";
    private static final String WRONG_PASSWORD = "mot-de-passe-incorrect";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Test
    void login_after6RapidFailedAttempts_correctPasswordStillSucceedsImmediately_provingNoAccountLockoutDos() {
        String email = "lockout-http@tontiflow.test";
        register(email, TEST_PASSWORD);

        // 6 requetes HTTP envoyees SANS attente entre elles : chacune recoit un simple
        // "mauvais mot de passe" (401), jamais autre chose - y compris celles arrivant
        // pendant le delai de 2s active par la 3e (le guard atomique cote base les rend
        // no-op sans jamais changer la reponse HTTP, cf. AuthAccountRepository).
        for (int i = 0; i < 6; i++) {
            ResponseEntity<ErrorResponse> attempt = loginExpectingError(email, WRONG_PASSWORD);
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // Preuve que le ralentissement est bien PERSISTE entre les requetes HTTP (chacune
        // sa propre transaction) - pas seulement observe via le code HTTP. Seules les 3
        // premieres requetes sont reellement comptabilisees : des la 3e, le delai de 2s
        // s'active (table R21-D.5) et les requetes 4 a 6, envoyees en quelques
        // millisecondes (bien avant l'expiration de ce delai), sont des no-op legitimes -
        // le compteur ne peut donc PAS depasser 3 dans ce scenario "rafale sans attente".
        // C'est le guard fonctionnant exactement comme concu, pas une anomalie.
        AuthAccount beforeSuccess = authAccountRepository.findByEmail(email).orElseThrow();
        assertThat(beforeSuccess.getFailedAttempts()).isEqualTo(3);
        assertThat(beforeSuccess.getNextAttemptAllowedAt()).isAfter(Instant.now()); // delai actif (2s)

        // 7e tentative, IMMEDIATEMENT, AVEC LE BON MOT DE PASSE : doit reussir SANS
        // attendre, malgre le delai encore actif - c'est la preuve directe que le deni de
        // service par verrouillage (D4-01, R21-D.4) est corrige : personne ne peut
        // empecher le titulaire legitime de se connecter en multipliant les mauvais mots
        // de passe.
        ResponseEntity<TokenResponse> success = login(email, TEST_PASSWORD);

        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(success.getBody()).isNotNull();
        assertThat(success.getBody().accessToken()).isNotBlank();

        // Le ralentissement est bien remis a zero apres le succes.
        AuthAccount afterSuccess = authAccountRepository.findByEmail(email).orElseThrow();
        assertThat(afterSuccess.getFailedAttempts()).isZero();
        assertThat(afterSuccess.getNextAttemptAllowedAt()).isNull();
    }

    @Test
    void login_withFewFailedAttempts_correctPasswordStillSucceeds() {
        String email = "lockout-http-ok@tontiflow.test";
        register(email, TEST_PASSWORD);

        // 2 echecs : palier "aucun delai" (1-2 echecs -> 0s).
        loginExpectingError(email, WRONG_PASSWORD);
        loginExpectingError(email, WRONG_PASSWORD);

        ResponseEntity<TokenResponse> response = login(email, TEST_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
    }

    @Test
    void login_wrongPasswordDuringActiveDelay_remainsGenericUnauthorized_indiscernableFromOrdinaryFailure() {
        String email = "lockout-http-paced@tontiflow.test";
        register(email, TEST_PASSWORD);

        // 3 echecs : franchit le premier palier avec delai (2s).
        for (int i = 0; i < 3; i++) {
            loginExpectingError(email, WRONG_PASSWORD);
        }

        // Nouvelle tentative IMMEDIATE (delai de 2s pas encore ecoule), toujours avec un
        // mauvais mot de passe : doit rester un 401 generique, identique a un echec
        // ordinaire - aucun signal distinct pour un delai en cours (anti-enumeration,
        // decision R21-D.5, meme principe que R21-D.3).
        ResponseEntity<ErrorResponse> paced = loginExpectingError(email, WRONG_PASSWORD);

        assertThat(paced.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(paced.getBody()).isNotNull();
        assertThat(paced.getBody().detail()).isEqualTo("Invalid credentials");
    }

    private ResponseEntity<Void> register(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/register", new RegisterRequest(email, password), Void.class);
    }

    private ResponseEntity<TokenResponse> login(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), TokenResponse.class);
    }

    private ResponseEntity<ErrorResponse> loginExpectingError(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), ErrorResponse.class);
    }
}
