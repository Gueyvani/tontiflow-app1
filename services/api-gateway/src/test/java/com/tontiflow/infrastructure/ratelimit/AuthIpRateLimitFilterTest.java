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

class AuthIpRateLimitFilterTest {

    private static final int LOGIN_LIMIT = 3;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AuthIpRateLimitFilter filter =
            new AuthIpRateLimitFilter(true, LOGIN_LIMIT, new ClientIpResolver(0), objectMapper, now::get);

    private static MockServerWebExchange login(String ip) {
        return request(HttpMethod.POST, "/api/v1/auth/login", ip);
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

    @Test
    void underLoginLimit_requestPassesThrough() {
        for (int i = 0; i < LOGIN_LIMIT; i++) {
            MockServerWebExchange exchange = login("10.0.0.1");
            assertThat(passesThrough(exchange)).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void exceedingLoginLimit_yields429WithRetryAfter_andChainNotInvoked() {
        for (int i = 0; i < LOGIN_LIMIT; i++) {
            passesThrough(login("10.0.0.2"));
        }
        MockServerWebExchange exchange = login("10.0.0.2");
        boolean passed = passesThrough(exchange);

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String retryAfter = exchange.getResponse().getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/json");

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("Trop de tentatives de connexion");
        assertThat(body).doesNotContain("password").doesNotContain("email");
    }

    @Test
    void afterWindowExpires_requestIsAcceptedAgain() {
        for (int i = 0; i < LOGIN_LIMIT; i++) {
            passesThrough(login("10.0.0.3"));
        }
        assertThat(passesThrough(login("10.0.0.3"))).isFalse();

        now.addAndGet(61_000L);

        assertThat(passesThrough(login("10.0.0.3"))).isTrue();
    }

    @Test
    void differentIps_haveIndependentCounters() {
        for (int i = 0; i < LOGIN_LIMIT; i++) {
            passesThrough(login("10.0.0.4"));
        }
        assertThat(passesThrough(login("10.0.0.4"))).isFalse();
        assertThat(passesThrough(login("10.0.0.5"))).isTrue();
    }

    @Test
    void nonLoginAuthPath_isNeverLimited() {
        for (int i = 0; i < LOGIN_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/auth/refresh", "10.0.0.6"))).isTrue();
        }
    }

    @Test
    void nonPostMethodOnLoginPath_isNeverLimited() {
        for (int i = 0; i < LOGIN_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/auth/login", "10.0.0.7"))).isTrue();
        }
    }

    @Test
    void trailingSlashLoginPath_isLimited() {
        for (int i = 0; i < LOGIN_LIMIT; i++) {
            passesThrough(request(HttpMethod.POST, "/api/v1/auth/login/", "10.0.0.8"));
        }
        assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/auth/login/", "10.0.0.8"))).isFalse();
    }

    @Test
    void whenDisabled_neverLimits() {
        AuthIpRateLimitFilter disabled =
                new AuthIpRateLimitFilter(false, 1, new ClientIpResolver(0), objectMapper, now::get);
        for (int i = 0; i < 10; i++) {
            AtomicInteger reached = new AtomicInteger();
            disabled.filter(login("10.0.0.9"), ex -> {
                reached.incrementAndGet();
                return Mono.empty();
            }).block();
            assertThat(reached.get()).isEqualTo(1);
        }
    }

    @Test
    void concurrentRequestsOnSameIp_areThreadSafe_andBounded() throws Exception {
        int limit = 5;
        int threads = 40;
        AuthIpRateLimitFilter concurrentFilter =
                new AuthIpRateLimitFilter(true, limit, new ClientIpResolver(0), objectMapper, now::get);

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
                    MockServerWebExchange exchange = login("10.9.9.9");
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
}
