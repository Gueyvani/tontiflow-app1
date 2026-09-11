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

class RefreshIpRateLimitFilterTest {

    private static final int REFRESH_LIMIT = 30;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RefreshIpRateLimitFilter filter =
            new RefreshIpRateLimitFilter(true, REFRESH_LIMIT, new ClientIpResolver(0), objectMapper, now::get);

    private static MockServerWebExchange refresh(String ip) {
        return request(HttpMethod.POST, "/api/v1/auth/refresh", ip);
    }

    private static MockServerWebExchange request(HttpMethod method, String path, String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path)
                        .remoteAddress(new InetSocketAddress(ip, 40000))
                        .build());
    }

    private boolean passesThrough(MockServerWebExchange exchange) {
        AtomicInteger reached = new AtomicInteger();
        GatewayFilterChain chain = ex -> {
            reached.incrementAndGet();
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return reached.get() == 1;
    }

    // Test 1 — nominal : moins de REFRESH_LIMIT requetes, toutes passent.
    @Test
    void underRefreshLimit_requestPassesThrough() {
        for (int i = 0; i < REFRESH_LIMIT; i++) {
            MockServerWebExchange exchange = refresh("10.0.1.1");
            assertThat(passesThrough(exchange)).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    // Test 2 — limite : les REFRESH_LIMIT premieres passent, la (REFRESH_LIMIT+1)-eme est bloquee.
    @Test
    void exceedingRefreshLimit_yields429_andChainNotInvoked() {
        for (int i = 0; i < REFRESH_LIMIT; i++) {
            assertThat(passesThrough(refresh("10.0.1.2"))).isTrue();
        }
        MockServerWebExchange exchange = refresh("10.0.1.2");
        boolean passed = passesThrough(exchange);

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // Test 3 — Retry-After present et de valeur valide, corps generique sans fuite d'information
    // sur la validite/existence/expiration/revocation du token presente.
    @Test
    void exceedingRefreshLimit_responseHasValidRetryAfter_andGenericBody() {
        for (int i = 0; i < REFRESH_LIMIT; i++) {
            passesThrough(refresh("10.0.1.3"));
        }
        MockServerWebExchange exchange = refresh("10.0.1.3");
        passesThrough(exchange);

        String retryAfter = exchange.getResponse().getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/json");

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("Trop de tentatives de renouvellement");
        assertThat(body).doesNotContain("token").doesNotContain("expire").doesNotContain("revoque")
                .doesNotContain("compte").doesNotContain("famille");
    }

    // Test 4 — deux IP distinctes ont des fenetres totalement independantes.
    @Test
    void differentIps_haveIndependentCounters() {
        for (int i = 0; i < REFRESH_LIMIT; i++) {
            passesThrough(refresh("10.0.1.4"));
        }
        assertThat(passesThrough(refresh("10.0.1.4"))).isFalse();
        assertThat(passesThrough(refresh("10.0.1.5"))).isTrue();
    }

    // Test 5 — /api/v1/auth/login n'est jamais affecte par ce filtre (chemin non cible).
    @Test
    void loginPath_isNeverLimitedByThisFilter() {
        for (int i = 0; i < REFRESH_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/auth/login", "10.0.1.6"))).isTrue();
        }
    }

    // Test 6 — GET /api/v1/auth/refresh n'est jamais limite (le filtre est POST-specifique,
    // meme comportement method-sensitive que AuthIpRateLimitFilter/ClaimIpRateLimitFilter).
    @Test
    void nonPostMethodOnRefreshPath_isNeverLimited() {
        for (int i = 0; i < REFRESH_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/auth/refresh", "10.0.1.7"))).isTrue();
        }
    }

    // Test 7 — slash final : meme contrat que AuthIpRateLimitFilter (trailingSlashLoginPath_isLimited) -
    // /api/v1/auth/refresh/ est reconnu comme le meme chemin et est bien limite.
    @Test
    void trailingSlashRefreshPath_isLimited() {
        for (int i = 0; i < REFRESH_LIMIT; i++) {
            passesThrough(request(HttpMethod.POST, "/api/v1/auth/refresh/", "10.0.1.8"));
        }
        assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/auth/refresh/", "10.0.1.8"))).isFalse();
    }

    // Test 8 — concurrence : meme patron que AuthIpRateLimitFilterTest.concurrentRequestsOnSameIp_areThreadSafe_andBounded -
    // des requetes concurrentes sur la meme IP ne peuvent pas contourner le seuil.
    @Test
    void concurrentRequestsOnSameIp_areThreadSafe_andBounded() throws Exception {
        int limit = 5;
        int threads = 40;
        RefreshIpRateLimitFilter concurrentFilter =
                new RefreshIpRateLimitFilter(true, limit, new ClientIpResolver(0), objectMapper, now::get);

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
                    MockServerWebExchange exchange = refresh("10.9.8.8");
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
        RefreshIpRateLimitFilter disabled =
                new RefreshIpRateLimitFilter(false, 1, new ClientIpResolver(0), objectMapper, now::get);
        for (int i = 0; i < 10; i++) {
            AtomicInteger reached = new AtomicInteger();
            disabled.filter(refresh("10.0.1.9"), ex -> {
                reached.incrementAndGet();
                return Mono.empty();
            }).block();
            assertThat(reached.get()).isEqualTo(1);
        }
    }

    @Test
    void afterWindowExpires_requestIsAcceptedAgain() {
        for (int i = 0; i < REFRESH_LIMIT; i++) {
            passesThrough(refresh("10.0.1.10"));
        }
        assertThat(passesThrough(refresh("10.0.1.10"))).isFalse();

        now.addAndGet(61_000L);

        assertThat(passesThrough(refresh("10.0.1.10"))).isTrue();
    }
}
