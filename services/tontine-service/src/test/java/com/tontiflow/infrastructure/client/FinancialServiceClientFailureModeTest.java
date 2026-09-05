package com.tontiflow.infrastructure.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Modes d'échec réels de {@link FinancialServiceClient} (décision R5, §5/§6/§29) —
 * complète les scénarios déjà prouvés en Phase R4 (connexion refusée,
 * financial-service réellement arrêté) avec les deux cas restés non testés :
 *
 * <ol>
 *   <li>connexion TCP acceptée mais serveur ne répondant jamais (§5) — via un
 *       {@link ServerSocket} brut, {@code accept()} réel, aucune réponse écrite ;</li>
 *   <li>{@code financial-service} répondant HTTP 500/503 (§29.D/E) — via {@link
 *       HttpServer} (JDK, zéro nouvelle dépendance — aucune bibliothèque de
 *       stub HTTP type WireMock n'existe dans le monorepo, conformément à §38).</li>
 * </ol>
 *
 * <p>Aucun contexte Spring nécessaire : {@link FinancialServiceClient} est
 * instanciable directement avec une URL de base arbitraire.</p>
 */
class FinancialServiceClientFailureModeTest {

    private ServerSocket blackholeSocket;
    private ExecutorService blackholeExecutor;
    private HttpServer httpServer;

    @AfterEach
    void tearDown() throws IOException {
        if (blackholeExecutor != null) {
            blackholeExecutor.shutdownNow();
        }
        if (blackholeSocket != null && !blackholeSocket.isClosed()) {
            blackholeSocket.close();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /**
     * TEST timeout réel (§5) : le serveur accepte réellement la connexion TCP
     * (contrairement à R4, qui n'a testé que la connexion refusée) puis ne
     * répond jamais. Prouve que {@code tontine-service} ne reste pas bloqué
     * indéfiniment — le timeout de lecture (5 s, {@link FinancialServiceClient})
     * se déclenche réellement, dans une durée bornée et raisonnable.
     */
    @Test
    void recordContribution_whenServerAcceptsButNeverResponds_failsWithinConfiguredTimeout() throws IOException {
        blackholeSocket = new ServerSocket(0);
        int port = blackholeSocket.getLocalPort();
        blackholeExecutor = Executors.newSingleThreadExecutor();
        // Accepte reellement la connexion TCP (le "handshake" a bien lieu) puis
        // ne renvoie jamais rien - simule un serveur bloque, pas un serveur absent.
        blackholeExecutor.submit(() -> {
            try (Socket ignored = blackholeSocket.accept()) {
                Thread.sleep(30_000);
            } catch (Exception ignored) {
                // Fermeture normale en fin de test (socket ferme depuis @AfterEach).
            }
        });

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + port);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.recordContribution(1L, 1L, 1L, new BigDecimal("100.00"), "Bearer irrelevant"))
                .isInstanceOf(IllegalStateException.class)
                // Decision R14-B3-B (§4) : une erreur reseau/timeout doit rester
                // distinguable en interne (ResourceAccessException) d'une vraie
                // reponse HTTP de Financial - le contrat public (IllegalStateException,
                // meme message) reste lui strictement inchange.
                .extracting(Throwable::getCause)
                .isInstanceOf(ResourceAccessException.class);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        // Doit se declencher proche des 5s configures, jamais rester bloque
        // indefiniment (pas de boucle infinie, pas de fuite de thread evidente
        // - le timeout JDK HttpURLConnection interrompt reellement l'appel).
        assertThat(elapsedSeconds).isLessThan(15L);
    }

    /**
     * TEST HTTP 500 réel (§29.D) : {@code financial-service} répond mais signale
     * une erreur serveur — doit être traduit en échec contrôlé, jamais en
     * exception non gérée fuitant vers l'appelant HTTP public.
     */
    @Test
    void recordContribution_whenFinancialServiceReturns500_failsControlledWithoutLeakingRawException() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/contributions", exchange -> {
            byte[] body = "internal error".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort());

        assertThatThrownBy(() -> client.recordContribution(1L, 1L, 1L, new BigDecimal("100.00"), "Bearer irrelevant"))
                .isInstanceOf(IllegalStateException.class)
                // Le message reste generique (§8) - jamais le corps brut de la reponse distante.
                .hasMessageNotContaining("internal error");
    }

    /**
     * TEST HTTP 503 réel (§29.E) : même exigence de contrôle que 500.
     */
    @Test
    void recordContribution_whenFinancialServiceReturns503_failsControlledWithoutLeakingRawException() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/internal/contributions", exchange -> {
            byte[] body = "service unavailable".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.start();

        FinancialServiceClient client = new FinancialServiceClient("http://localhost:" + httpServer.getAddress().getPort());

        assertThatThrownBy(() -> client.recordContribution(1L, 1L, 1L, new BigDecimal("100.00"), "Bearer irrelevant"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("service unavailable")
                // Decision R14-B3-B (§4) : cause technique exacte conservee en
                // interne (vraie reponse HTTP de Financial, code 503 source).
                .extracting(Throwable::getCause)
                .isInstanceOf(RestClientResponseException.class)
                .extracting(cause -> ((RestClientResponseException) cause).getStatusCode().value())
                .isEqualTo(503);
    }
}
