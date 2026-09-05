package com.tontiflow.infrastructure.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void getBalance_sendsCorrectRequestAndAuthorizationHeader_andParsesResponse() throws IOException {
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

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort());

        AccountBalanceResponse response = client.getBalance(10L, "Bearer real-caller-token");

        assertThat(receivedMethod.get()).isEqualTo("GET");
        assertThat(receivedPath.get()).isEqualTo("/internal/accounts/10/TONTINE/balance");
        // Transmission verbatim du JWT de l'appelant original (decision R3/R27) - jamais parse, jamais reconstruit.
        assertThat(receivedAuthorization.get()).isEqualTo("Bearer real-caller-token");
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

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort());

        AccountBalanceResponse response = client.getMemberBalance(100L, "Bearer irrelevant");

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

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort());

        List<LedgerLineResponse> lines = client.getStatement(10L, "Bearer irrelevant");

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

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort());

        List<LedgerLineResponse> lines = client.getMemberStatement(100L, "Bearer irrelevant");

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

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort());

        assertThatThrownBy(() -> client.getBalance(10L, "Bearer irrelevant"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("internal error");
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

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort());

        assertThatThrownBy(() -> client.getStatement(10L, "Bearer irrelevant"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("internal error");
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
