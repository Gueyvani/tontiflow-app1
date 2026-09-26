package com.tontiflow.infrastructure.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tontiflow.security.jwt.ServiceTokenCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Couvre les appels réels de {@link FinancialServiceClient} restés faiblement
 * testés (décision R11, corrections techniques — audit R11 §21 : couverture
 * JaCoCo réelle de {@code FinancialServiceClient} = 37 %, la construction
 * HTTP réelle {@code restClient.get()/post()} n'étant exercée que par des
 * validations manuelles éphémères, jamais par un test JUnit permanent).
 *
 * <p>Réutilise exactement le pattern déjà établi et validé de {@link
 * FinancialServiceClientFailureModeTest} (décision R5) : {@link HttpServer}
 * du JDK, zéro nouvelle dépendance (ni WireMock, ni MockRestServiceServer —
 * ce dernier exigerait d'exposer un {@code RestClient.Builder} injectable,
 * un changement de contrat de constructeur non nécessaire ici). {@link
 * FinancialServiceClient} est instanciable directement avec une URL de base
 * arbitraire, sans contexte Spring.</p>
 */
class FinancialServiceClientTest {

    private static final String TEST_SECRET = "tontine-client-test-only-secret-0123456789";
    private static final java.util.UUID ON_BEHALF_OF = java.util.UUID.randomUUID();

    private final ServiceTokenCodec codec = new ServiceTokenCodec(TEST_SECRET, Clock.systemUTC());

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        // Decision R14-B3-B : ne jamais laisser un contexte de requete fictif
        // fuiter vers le test suivant (tests sans RequestContextHolder actif
        // par defaut, comme avant cette decision).
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getBalance_sendsCorrectRequestAndServiceToken_andParsesResponse() throws IOException {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        AtomicReference<String> receivedMethod = new AtomicReference<>();

        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/10/TONTINE/balance", exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            receivedMethod.set(exchange.getRequestMethod());
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, 200, "{\"currency\":\"MRU\",\"balance\":700.00}");
        });
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);

        AccountBalanceResponse response = client.getBalance(10L, ON_BEHALF_OF);

        assertThat(receivedMethod.get()).isEqualTo("GET");
        assertThat(receivedPath.get()).isEqualTo("/internal/accounts/10/TONTINE/balance");
        // Decision F-8 : jeton de service (HS256, portee de lecture) - jamais le JWT de l'utilisateur.
        assertThat(receivedAuthorization.get()).startsWith("Bearer ");
        ServiceTokenCodec.ServiceTokenClaims claims = codec.verify(
                receivedAuthorization.get().substring("Bearer ".length()),
                ServiceTokenCodec.SERVICE_TONTINE, ServiceTokenCodec.SERVICE_FINANCIAL);
        assertThat(claims.scopes()).containsExactly(ServiceTokenCodec.SCOPE_LEDGER_READ);
        assertThat(claims.onBehalfOf()).isEqualTo(ON_BEHALF_OF);
        assertThat(response.currency()).isEqualTo("MRU");
        assertThat(response.balance()).isEqualByComparingTo("700.00");
    }

    @Test
    void getMemberBalance_sendsRequestToMemberAccountType() throws IOException {
        AtomicReference<String> receivedPath = new AtomicReference<>();

        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/100/MEMBER/balance", exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            writeJson(exchange, 200, "{\"currency\":\"MRU\",\"balance\":-500.00}");
        });
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);

        AccountBalanceResponse response = client.getMemberBalance(100L, ON_BEHALF_OF);

        assertThat(receivedPath.get()).isEqualTo("/internal/accounts/100/MEMBER/balance");
        assertThat(response.balance()).isEqualByComparingTo("-500.00");
    }

    @Test
    void getStatement_parsesListResponseWithMultipleLines() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/10/TONTINE/lines", exchange -> writeJson(exchange, 200, """
                [
                  {"eventType":"CONTRIBUTION_RECORDED","description":"Contribution round 1 tontine 10",
                   "debit":1200.00,"credit":0.00,"currency":"MRU","createdAt":"2026-09-04T18:56:44.293626Z"},
                  {"eventType":"DISBURSEMENT_RECORDED","description":"Versement round 2 tontine 10",
                   "debit":0.00,"credit":400.00,"currency":"MRU","createdAt":"2026-09-04T18:56:44.455035Z"}
                ]
                """));
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);

        List<LedgerLineResponse> lines = client.getStatement(10L, ON_BEHALF_OF);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).eventType()).isEqualTo("CONTRIBUTION_RECORDED");
        assertThat(lines.get(0).debit()).isEqualByComparingTo("1200.00");
        assertThat(lines.get(1).eventType()).isEqualTo("DISBURSEMENT_RECORDED");
        assertThat(lines.get(1).credit()).isEqualByComparingTo("400.00");
    }

    @Test
    void getMemberStatement_parsesListResponse() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/100/MEMBER/lines", exchange -> writeJson(exchange, 200, """
                [{"eventType":"CONTRIBUTION_RECORDED","description":"desc",
                  "debit":0.00,"credit":1200.00,"currency":"MRU","createdAt":"2026-09-04T18:56:44.293626Z"}]
                """));
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);

        List<LedgerLineResponse> lines = client.getMemberStatement(100L, ON_BEHALF_OF);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).credit()).isEqualByComparingTo("1200.00");
    }

    @Test
    void getBalance_whenFinancialServiceReturns500_failsControlledWithoutLeakingRawException() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/10/TONTINE/balance", exchange -> {
            byte[] body = "internal error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);

        assertThatThrownBy(() -> client.getBalance(10L, ON_BEHALF_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("internal error")
                // Decision R14-B3-B (§4) : la cause technique exacte - ici une vraie
                // reponse HTTP de Financial, avec son code de statut source - reste
                // disponible en interne, meme si le contrat HTTP public (IllegalStateException,
                // meme message) est strictement inchange.
                .extracting(Throwable::getCause)
                .isInstanceOf(RestClientResponseException.class)
                .extracting(cause -> ((RestClientResponseException) cause).getStatusCode().value())
                .isEqualTo(500);
    }

    @Test
    void getStatement_whenFinancialServiceReturns500_failsControlledWithoutLeakingRawException() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/10/TONTINE/lines", exchange -> {
            byte[] body = "internal error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);

        assertThatThrownBy(() -> client.getStatement(10L, ON_BEHALF_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("internal error");
    }

    @Test
    void recordContribution_whenIncomingRequestHasCorrelationId_forwardsItToFinancialService() throws IOException {
        AtomicReference<String> receivedCorrelationId = new AtomicReference<>();

        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/contributions", exchange -> {
            receivedCorrelationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        httpServer.start();

        MockHttpServletRequest incomingRequest = new MockHttpServletRequest();
        incomingRequest.addHeader("X-Correlation-ID", "abc-123-real-request");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incomingRequest));

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);
        client.recordContribution(1L, 1L, 1L, new BigDecimal("500.00"), ON_BEHALF_OF);

        assertThat(receivedCorrelationId.get()).isEqualTo("abc-123-real-request");
    }

    @Test
    void getBalance_whenIncomingRequestHasCorrelationId_forwardsItToFinancialService() throws IOException {
        AtomicReference<String> receivedCorrelationId = new AtomicReference<>();

        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/10/TONTINE/balance", exchange -> {
            receivedCorrelationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            writeJson(exchange, 200, "{\"currency\":\"MRU\",\"balance\":0.00}");
        });
        httpServer.start();

        MockHttpServletRequest incomingRequest = new MockHttpServletRequest();
        incomingRequest.addHeader("X-Correlation-ID", "def-456-real-request");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incomingRequest));

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);
        client.getBalance(10L, ON_BEHALF_OF);

        assertThat(receivedCorrelationId.get()).isEqualTo("def-456-real-request");
    }

    @Test
    void recordContribution_whenNoIncomingCorrelationId_sendsNoCorrelationHeader() throws IOException {
        AtomicReference<String> receivedCorrelationId = new AtomicReference<>();
        AtomicReference<Boolean> headerPresent = new AtomicReference<>();

        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/contributions", exchange -> {
            headerPresent.set(exchange.getRequestHeaders().containsKey("X-Correlation-ID"));
            receivedCorrelationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        httpServer.start();

        // Decision R14-B3-B (§3) : aucun contexte de requete entrante actif ici -
        // comportement deja etabli avant cette decision, ne doit jamais generer
        // un identifiant de remplacement.
        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort(), codec);
        client.recordContribution(1L, 1L, 1L, new BigDecimal("500.00"), ON_BEHALF_OF);

        assertThat(headerPresent.get()).isFalse();
        assertThat(receivedCorrelationId.get()).isNull();
    }

    // ------------------------------------------------------------------
    // Decision F-8 : jeton de service par appel, portee selon la methode, aucun JWT utilisateur transmis.
    // ------------------------------------------------------------------

    @Test
    void recordContribution_sendsWriteScopedServiceToken_withCallerAsOnBehalfOf() throws IOException {
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/contributions", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient(
                "http://localhost:" + httpServer.getAddress().getPort(), codec);
        client.recordContribution(1L, 1L, 1L, new BigDecimal("500.00"), ON_BEHALF_OF);

        ServiceTokenCodec.ServiceTokenClaims claims = codec.verify(
                receivedAuthorization.get().substring("Bearer ".length()),
                ServiceTokenCodec.SERVICE_TONTINE, ServiceTokenCodec.SERVICE_FINANCIAL);
        assertThat(claims.scopes()).containsExactly(ServiceTokenCodec.SCOPE_LEDGER_WRITE);
        assertThat(claims.onBehalfOf()).isEqualTo(ON_BEHALF_OF);
        assertThat(claims.subject()).isEqualTo("service:tontine-service");
    }

    @Test
    void everyCall_issuesAFreshShortLivedToken() throws IOException {
        java.util.List<String> tokens = new java.util.concurrent.CopyOnWriteArrayList<>();
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/10/TONTINE/balance", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, 200, "{\"currency\":\"MRU\",\"balance\":0.00}");
        });
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient(
                "http://localhost:" + httpServer.getAddress().getPort(), codec);
        client.getBalance(10L, ON_BEHALF_OF);
        client.getBalance(10L, ON_BEHALF_OF);

        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0)).isNotEqualTo(tokens.get(1));
    }

    @Test
    void userAuthorizationHeaderOfTheIncomingRequest_isNeverForwarded() throws IOException {
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/accounts/10/TONTINE/balance", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, 200, "{\"currency\":\"MRU\",\"balance\":0.00}");
        });
        httpServer.start();

        MockHttpServletRequest incomingRequest = new MockHttpServletRequest();
        incomingRequest.addHeader("Authorization", "Bearer user-jwt-must-not-leak");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incomingRequest));

        FinancialServiceClient client = new FinancialServiceClient(
                "http://localhost:" + httpServer.getAddress().getPort(), codec);
        client.getBalance(10L, ON_BEHALF_OF);

        assertThat(receivedAuthorization.get()).doesNotContain("user-jwt-must-not-leak");
        assertThat(receivedAuthorization.get()).startsWith("Bearer ");
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
