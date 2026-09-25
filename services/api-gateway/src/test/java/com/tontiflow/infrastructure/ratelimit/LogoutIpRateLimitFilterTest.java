package com.tontiflow.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests unitaires de {@link LogoutIpRateLimitFilter} (TICKET-4, constat F-1) : mêmes conventions que RefreshIpRateLimitFilterTest. */
class LogoutIpRateLimitFilterTest {

    private static final int LOGOUT_LIMIT = 30;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final LogoutIpRateLimitFilter filter =
            new LogoutIpRateLimitFilter(true, LOGOUT_LIMIT, new ClientIpResolver(0), objectMapper, now::get);

    private static MockServerWebExchange logout(String ip) {
        return request(HttpMethod.POST, "/api/v1/auth/logout", ip);
    }

    private static MockServerWebExchange request(HttpMethod method, String path, String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path)
                        .remoteAddress(new InetSocketAddress(ip, 40000))
                        .build());
    }

    private static boolean passesThrough(java.util.function.BiFunction<
            MockServerWebExchange, GatewayFilterChain, Mono<Void>> target, MockServerWebExchange exchange) {
        AtomicInteger reached = new AtomicInteger();
        GatewayFilterChain chain = ex -> {
            reached.incrementAndGet();
            return Mono.empty();
        };
        target.apply(exchange, chain).block();
        return reached.get() == 1;
    }

    private boolean passesThrough(MockServerWebExchange exchange) {
        return passesThrough(filter::filter, exchange);
    }

    @Test
    void underLogoutLimit_requestPassesThrough() {
        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            MockServerWebExchange exchange = logout("10.0.4.1");
            assertThat(passesThrough(exchange)).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void exceedingLogoutLimit_yields429_andChainNotInvoked() {
        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            assertThat(passesThrough(logout("10.0.4.2"))).isTrue();
        }
        MockServerWebExchange exchange = logout("10.0.4.2");

        assertThat(passesThrough(exchange)).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void exceedingLogoutLimit_responseHasValidRetryAfter_andGenericBody() {
        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            passesThrough(logout("10.0.4.3"));
        }
        MockServerWebExchange exchange = logout("10.0.4.3");
        passesThrough(exchange);

        String retryAfter = exchange.getResponse().getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/json");
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("Trop de requêtes de déconnexion");
        assertThat(body).doesNotContain("token").doesNotContain("expire").doesNotContain("revoque")
                .doesNotContain("compte").doesNotContain("famille");
    }

    @Test
    void differentIps_haveIndependentCounters() {
        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            passesThrough(logout("10.0.4.4"));
        }
        assertThat(passesThrough(logout("10.0.4.4"))).isFalse();
        assertThat(passesThrough(logout("10.0.4.5"))).isTrue();
    }

    // Compteur INDEPENDANT de /refresh : epuiser le quota logout ne limite pas refresh, et inversement.
    @Test
    void logoutAndRefreshCounters_areIndependent() {
        RefreshIpRateLimitFilter refreshFilter =
                new RefreshIpRateLimitFilter(true, LOGOUT_LIMIT, new ClientIpResolver(0), objectMapper, now::get);

        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            assertThat(passesThrough(logout("10.0.4.6"))).isTrue();
        }
        assertThat(passesThrough(logout("10.0.4.6"))).isFalse(); // logout epuise
        assertThat(passesThrough(refreshFilter::filter,
                request(HttpMethod.POST, "/api/v1/auth/refresh", "10.0.4.6"))).isTrue(); // refresh intact

        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            assertThat(passesThrough(refreshFilter::filter,
                    request(HttpMethod.POST, "/api/v1/auth/refresh", "10.0.4.7"))).isTrue();
        }
        assertThat(passesThrough(refreshFilter::filter,
                request(HttpMethod.POST, "/api/v1/auth/refresh", "10.0.4.7"))).isFalse(); // refresh epuise
        assertThat(passesThrough(logout("10.0.4.7"))).isTrue(); // logout intact
    }

    @Test
    void otherAuthPaths_areNeverLimitedByThisFilter() {
        for (String path : new String[] {"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/register"}) {
            for (int i = 0; i < LOGOUT_LIMIT + 5; i++) {
                assertThat(passesThrough(request(HttpMethod.POST, path, "10.0.4.8"))).isTrue();
            }
        }
    }

    @Test
    void nonPostMethodOnLogoutPath_isNeverLimited() {
        for (int i = 0; i < LOGOUT_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/auth/logout", "10.0.4.9"))).isTrue();
        }
    }

    @Test
    void trailingSlashLogoutPath_isLimited() {
        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            passesThrough(request(HttpMethod.POST, "/api/v1/auth/logout/", "10.0.4.10"));
        }
        assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/auth/logout/", "10.0.4.10"))).isFalse();
    }

    @Test
    void concurrentRequestsOnSameIp_areThreadSafe_andBounded() throws Exception {
        int limit = 5;
        int threads = 40;
        LogoutIpRateLimitFilter concurrentFilter =
                new LogoutIpRateLimitFilter(true, limit, new ClientIpResolver(0), objectMapper, now::get);

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(16);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    MockServerWebExchange exchange = logout("10.9.7.7");
                    AtomicInteger reached = new AtomicInteger();
                    concurrentFilter.filter(exchange, ex -> {
                        reached.incrementAndGet();
                        return Mono.empty();
                    }).block();
                    if (reached.get() == 1) {
                        allowed.incrementAndGet();
                    } else if (exchange.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(errors).hasValue(0);
        assertThat(allowed.get() + rejected.get()).isEqualTo(threads);
        assertThat(allowed.get()).isBetween(limit, limit + 10);
    }

    @Test
    void whenDisabled_neverLimits() {
        LogoutIpRateLimitFilter disabled =
                new LogoutIpRateLimitFilter(false, 1, new ClientIpResolver(0), objectMapper, now::get);
        for (int i = 0; i < 10; i++) {
            assertThat(passesThrough(disabled::filter, logout("10.0.4.11"))).isTrue();
        }
    }

    @Test
    void afterWindowExpires_requestIsAcceptedAgain() {
        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            passesThrough(logout("10.0.4.12"));
        }
        assertThat(passesThrough(logout("10.0.4.12"))).isFalse();

        now.addAndGet(61_000L);

        assertThat(passesThrough(logout("10.0.4.12"))).isTrue();
    }

    // ------------------------------------------------------------------
    // TICKET-5 (F-7) : variantes percent-encodees du chemin (memes segments que
    // Spring Security / Gateway). Les requetes utilisent URI.create : le helper
    // MockServerHttpRequest.method(method, "<template>") reencoderait % en %25.
    // ------------------------------------------------------------------

    private static final String CANONICAL_PATH = "/api/v1/auth/logout";
    private static final String[] ENCODED_VARIANTS = {"/api/v1/auth/logou%74", "/api/v1/auth/%6Cogout"};

    private static MockServerWebExchange encodedRequest(HttpMethod method, String rawPath, String ip) {
        java.net.URI uri = java.net.URI.create(rawPath);
        assertThat(uri.getRawPath()).isEqualTo(rawPath);
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(method, uri)
                        .remoteAddress(new InetSocketAddress(ip, 40000))
                        .build());
    }

    @Test
    void encodedVariants_shareTheCanonicalQuota() {
        String ip = "10.20.6.1";
        // Le quota est consomme alternativement par le chemin canonique et ses variantes encodees.
        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            String path = (i % (ENCODED_VARIANTS.length + 1) == 0)
                    ? CANONICAL_PATH : ENCODED_VARIANTS[(i - 1) % ENCODED_VARIANTS.length];
            assertThat(passesThrough(encodedRequest(HttpMethod.POST, path, ip))).as(path).isTrue();
        }

        // Quota epuise : le canonique ET chaque variante sont refuses, avec le meme compteur.
        assertThat(passesThrough(encodedRequest(HttpMethod.POST, CANONICAL_PATH, ip))).isFalse();
        for (String variant : ENCODED_VARIANTS) {
            MockServerWebExchange exchange = encodedRequest(HttpMethod.POST, variant, ip);
            assertThat(passesThrough(exchange)).as(variant).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        // Avec slash final, toujours le meme compteur (contrat historique conserve).
        assertThat(passesThrough(encodedRequest(HttpMethod.POST, ENCODED_VARIANTS[0] + "/", ip))).isFalse();
    }

    @Test
    void encodedVariantsAlone_exhaustTheQuota_withoutAnyCanonicalRequest() {
        String ip = "10.20.6.2";
        for (int i = 0; i < LOGOUT_LIMIT; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.POST, ENCODED_VARIANTS[i % ENCODED_VARIANTS.length], ip))).isTrue();
        }
        assertThat(passesThrough(encodedRequest(HttpMethod.POST, CANONICAL_PATH, ip))).isFalse();
    }

    @Test
    void encodedNeighborPath_isNeverLimited() {
        for (int i = 0; i < LOGOUT_LIMIT + 5; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.POST, "/api/v1/auth/logou%74x", "10.20.6.4"))).isTrue();
        }
    }

    @Test
    void encodedVariant_withNonPostMethod_isNeverLimited() {
        for (int i = 0; i < LOGOUT_LIMIT + 5; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.GET, ENCODED_VARIANTS[0], "10.20.6.3"))).isTrue();
        }
    }
}
