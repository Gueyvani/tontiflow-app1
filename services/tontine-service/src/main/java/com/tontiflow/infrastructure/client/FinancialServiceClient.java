package com.tontiflow.infrastructure.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Client HTTP service-à-service vers {@code financial-service} (décision
 * R3, §10/§27) : {@link RestClient} (déjà disponible via {@code
 * spring-boot-starter-web}, aucune nouvelle dépendance type OpenFeign,
 * absent du monorepo — vérifié à l'inspection).
 *
 * <p>URL de base : réutilise exactement le même nom de variable
 * d'environnement que la Gateway ({@code FINANCIAL_SERVICE_URL}, cf. {@code
 * api-gateway/application.yml}), même valeur par défaut — cohérence entre
 * les deux points d'accès à {@code financial-service}.</p>
 *
 * <p>Timeout localisé à ce client uniquement (5 s connexion/lecture) — ne
 * modifie aucune configuration globale d'un autre service (§28).</p>
 *
 * <p>Le JWT de l'appelant original est transmis tel quel (jamais parsé, ni
 * loggé) : {@code financial-service} authentifie la requête via sa propre
 * chaîne {@code JwtAuthenticationFilter} déjà existante, sans aucune
 * modification de sa {@code SecurityConfig}. L'autorisation métier
 * (créateur, appartenance membre/round) reste entièrement de la
 * responsabilité de {@code tontine-service}, exécutée <i>avant</i> cet
 * appel — {@code financial-service} ne réévalue jamais cette autorisation,
 * il fait confiance à la validation déjà effectuée par l'appelant interne.</p>
 *
 * <p><b>Décision R14-B3-B</b> : le {@code X-Correlation-ID} de la requête
 * HTTP entrante (déjà lu par {@code GlobalExceptionHandler} pour son propre
 * corps d'erreur) est transmis tel quel à {@code financial-service} sur les
 * 4 appels ci-dessous, uniquement s'il est déjà présent — jamais généré ici
 * pour éviter de diverger de l'identifiant que {@code GlobalExceptionHandler}
 * utiliserait pour la même requête en son absence. Le contrat HTTP public
 * (code de statut, corps de réponse) reste strictement inchangé : la cause
 * technique exacte ({@link RestClientResponseException} avec son statut
 * source, vs {@link ResourceAccessException} réseau/timeout) est seulement
 * journalisée en interne (jamais le JWT/{@code Authorization}), jamais
 * exposée au client final.</p>
 */
@Component
public class FinancialServiceClient {

    private static final Logger log = LoggerFactory.getLogger(FinancialServiceClient.class);

    private static final String CURRENCY_MRU = "MRU";
    private static final String ACCOUNT_TYPE_TONTINE = "TONTINE";
    private static final String ACCOUNT_TYPE_MEMBER = "MEMBER";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public FinancialServiceClient(@Value("${financial-service.url:http://localhost:8083}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(TIMEOUT)
                .withReadTimeout(TIMEOUT);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Enregistre une contribution auprès de {@code financial-service}.
     * Idempotent côté serveur (délégué à {@code ContributionService}/{@code
     * LedgerService}) — un appel répété avec les mêmes identifiants
     * métier ne crée jamais une seconde écriture financière.
     *
     * @throws IllegalStateException si l'appel échoue (réseau, timeout, erreur serveur)
     */
    public void recordContribution(Long tontineId, Long roundId, Long memberId, BigDecimal amount,
                                    String authorizationHeader) {
        RecordContributionPayload payload =
                new RecordContributionPayload(tontineId, roundId, memberId, amount, CURRENCY_MRU);
        try {
            restClient.post()
                    .uri("/internal/contributions")
                    .headers(headers -> setOutgoingHeaders(headers, authorizationHeader))
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            logFailure(e, "/internal/contributions");
            throw new IllegalStateException("Échec de l'enregistrement de la contribution auprès de financial-service", e);
        }
    }

    /**
     * Enregistre un versement au bénéficiaire d'un round auprès de {@code
     * financial-service} (décision R6, symétrique à {@link
     * #recordContribution}). Idempotent côté serveur.
     *
     * @throws IllegalStateException si l'appel échoue (réseau, timeout, erreur serveur)
     */
    public void recordDisbursement(Long tontineId, Long roundId, Long beneficiaryId, BigDecimal amount,
                                    String authorizationHeader) {
        RecordDisbursementPayload payload =
                new RecordDisbursementPayload(tontineId, roundId, beneficiaryId, amount, CURRENCY_MRU);
        try {
            restClient.post()
                    .uri("/internal/disbursements")
                    .headers(headers -> setOutgoingHeaders(headers, authorizationHeader))
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            logFailure(e, "/internal/disbursements");
            throw new IllegalStateException("Échec de l'enregistrement du versement auprès de financial-service", e);
        }
    }

    /**
     * Consulte le solde du compte TONTINE d'une tontine auprès de {@code
     * financial-service} (décision R7). Lecture pure : aucune écriture,
     * aucune création de compte. {@code ownerReference}/{@code accountType}
     * dérivés exclusivement de {@code tontineId} — jamais fournis par le
     * client (§4.1, décision R7).
     *
     * @throws IllegalStateException si l'appel échoue (réseau, timeout, erreur serveur)
     */
    public AccountBalanceResponse getBalance(Long tontineId, String authorizationHeader) {
        return fetchBalance(tontineId, ACCOUNT_TYPE_TONTINE, authorizationHeader);
    }

    /**
     * Consulte le solde du compte MEMBER d'une adhésion auprès de {@code
     * financial-service} (décision R8, symétrique à {@link #getBalance} —
     * décision R7). {@code ownerReference} = {@code TontineMember.id}
     * (identité de l'adhésion, jamais {@code TontineMember.userId}), dérivé
     * exclusivement de {@code memberId} — jamais fourni librement par le
     * client sans revalidation d'appartenance (effectuée en amont côté
     * {@code BalanceApplicationService}).
     *
     * @throws IllegalStateException si l'appel échoue (réseau, timeout, erreur serveur)
     */
    public AccountBalanceResponse getMemberBalance(Long memberId, String authorizationHeader) {
        return fetchBalance(memberId, ACCOUNT_TYPE_MEMBER, authorizationHeader);
    }

    private AccountBalanceResponse fetchBalance(Long ownerReference, String accountType, String authorizationHeader) {
        String uri = "/internal/accounts/" + ownerReference + "/" + accountType + "/balance";
        try {
            return restClient.get()
                    .uri("/internal/accounts/{ownerReference}/{accountType}/balance", ownerReference, accountType)
                    .headers(headers -> setOutgoingHeaders(headers, authorizationHeader))
                    .retrieve()
                    .body(AccountBalanceResponse.class);
        } catch (RestClientException e) {
            logFailure(e, uri);
            throw new IllegalStateException("Échec de la consultation du solde auprès de financial-service", e);
        }
    }

    /**
     * Consulte le relevé du compte TONTINE d'une tontine auprès de {@code
     * financial-service} (décision R10, symétrique à {@link #getBalance}).
     * Lecture pure.
     *
     * @throws IllegalStateException si l'appel échoue (réseau, timeout, erreur serveur)
     */
    public List<LedgerLineResponse> getStatement(Long tontineId, String authorizationHeader) {
        return fetchStatement(tontineId, ACCOUNT_TYPE_TONTINE, authorizationHeader);
    }

    /**
     * Consulte le relevé du compte MEMBER d'une adhésion auprès de {@code
     * financial-service} (décision R10, symétrique à {@link
     * #getMemberBalance}). Lecture pure.
     *
     * @throws IllegalStateException si l'appel échoue (réseau, timeout, erreur serveur)
     */
    public List<LedgerLineResponse> getMemberStatement(Long memberId, String authorizationHeader) {
        return fetchStatement(memberId, ACCOUNT_TYPE_MEMBER, authorizationHeader);
    }

    private List<LedgerLineResponse> fetchStatement(Long ownerReference, String accountType, String authorizationHeader) {
        String uri = "/internal/accounts/" + ownerReference + "/" + accountType + "/lines";
        try {
            return restClient.get()
                    .uri("/internal/accounts/{ownerReference}/{accountType}/lines", ownerReference, accountType)
                    .headers(headers -> setOutgoingHeaders(headers, authorizationHeader))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<LedgerLineResponse>>() { });
        } catch (RestClientException e) {
            logFailure(e, uri);
            throw new IllegalStateException("Échec de la consultation du relevé auprès de financial-service", e);
        }
    }

    /**
     * Positionne l'en-tête {@code Authorization} (toujours) et, si présent
     * sur la requête HTTP entrante, l'en-tête {@code X-Correlation-ID}
     * (décision R14-B3-B) — jamais généré ici (§3, périmètre strict).
     */
    private static void setOutgoingHeaders(HttpHeaders headers, String authorizationHeader) {
        headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        String correlationId = currentCorrelationId();
        if (correlationId != null) {
            headers.set(CORRELATION_ID_HEADER, correlationId);
        }
    }

    /**
     * Lit le {@code X-Correlation-ID} de la requête HTTP entrante courante,
     * si elle existe et si l'en-tête est présent — {@code null} sinon.
     * N'en génère jamais un nouveau (décision R14-B3-B, §3) : ce n'est pas
     * le rôle de ce client, uniquement celui de {@code GlobalExceptionHandler}
     * pour son propre corps de réponse.
     */
    private static String currentCorrelationId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
            String header = servletAttributes.getRequest().getHeader(CORRELATION_ID_HEADER);
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        return null;
    }

    /**
     * Journalise la cause technique exacte d'un échec d'appel à
     * {@code financial-service} (décision R14-B3-B, §4) — distingue une
     * vraie réponse HTTP de Financial ({@link RestClientResponseException},
     * avec son code de statut source) d'une erreur réseau/timeout
     * ({@link ResourceAccessException}), sans jamais journaliser le JWT/
     * {@code Authorization} ni modifier le comportement HTTP public (§21,
     * toujours {@link IllegalStateException} avec le même message).
     */
    private static void logFailure(RestClientException e, String endpoint) {
        String correlationId = currentCorrelationId();
        if (e instanceof RestClientResponseException responseException) {
            log.warn("Appel financial-service en echec (HTTP {}) - endpoint={} correlationId={}",
                    responseException.getStatusCode().value(), endpoint, correlationId);
        } else if (e instanceof ResourceAccessException) {
            log.warn("Appel financial-service en echec (reseau/timeout) - endpoint={} correlationId={}",
                    endpoint, correlationId);
        } else {
            log.warn("Appel financial-service en echec (cause non classifiee: {}) - endpoint={} correlationId={}",
                    e.getClass().getSimpleName(), endpoint, correlationId);
        }
    }
}
