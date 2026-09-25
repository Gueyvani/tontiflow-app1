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

class ClaimIpRateLimitFilterTest {

    private static final int IP_LIMIT = 3;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ClaimIpRateLimitFilter filter =
            new ClaimIpRateLimitFilter(true, IP_LIMIT, new ClientIpResolver(0), objectMapper, now::get);

    private static MockServerWebExchange claim(String ip) {
        return request(HttpMethod.POST, "/api/v1/tontines/1/members/claim", ip);
    }

    private static MockServerWebExchange request(HttpMethod method, String path, String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path)
                        .remoteAddress(new InetSocketAddress(ip, 40000))
                        .build());
    }

    /** @return true si la requête a traversé la chaîne (non limitée). */
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
    void underIpLimit_requestPassesThrough() {
        for (int i = 0; i < IP_LIMIT; i++) {
            MockServerWebExchange exchange = claim("10.0.0.1");
            assertThat(passesThrough(exchange)).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void exceedingIpLimit_yields429WithRetryAfter_andChainNotInvoked() {
        for (int i = 0; i < IP_LIMIT; i++) {
            passesThrough(claim("10.0.0.2"));
        }
        MockServerWebExchange exchange = claim("10.0.0.2");
        boolean passed = passesThrough(exchange);

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String retryAfter = exchange.getResponse().getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/json");

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("Trop de tentatives");
        assertThat(body).doesNotContain("Invitation").doesNotContain("code");
    }

    @Test
    void afterWindowExpires_requestIsAcceptedAgain() {
        for (int i = 0; i < IP_LIMIT; i++) {
            passesThrough(claim("10.0.0.3"));
        }
        assertThat(passesThrough(claim("10.0.0.3"))).isFalse();

        now.addAndGet(61_000L);

        assertThat(passesThrough(claim("10.0.0.3"))).isTrue();
    }

    @Test
    void differentIps_haveIndependentCounters() {
        for (int i = 0; i < IP_LIMIT; i++) {
            passesThrough(claim("10.0.0.4"));
        }
        assertThat(passesThrough(claim("10.0.0.4"))).isFalse();
        assertThat(passesThrough(claim("10.0.0.5"))).isTrue();
    }

    @Test
    void nonClaimPath_isNeverLimited() {
        for (int i = 0; i < IP_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/tontines/1/members", "10.0.0.6"))).isTrue();
        }
    }

    @Test
    void nonPostMethodOnClaimPath_isNeverLimited() {
        for (int i = 0; i < IP_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/tontines/1/members/claim", "10.0.0.7"))).isTrue();
        }
    }

    @Test
    void trailingSlashClaimPath_isLimited() {
        for (int i = 0; i < IP_LIMIT; i++) {
            passesThrough(request(HttpMethod.POST, "/api/v1/tontines/1/members/claim/", "10.0.0.8"));
        }
        assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/tontines/1/members/claim/", "10.0.0.8"))).isFalse();
    }

    @Test
    void whenDisabled_neverLimits() {
        ClaimIpRateLimitFilter disabled =
                new ClaimIpRateLimitFilter(false, 1, new ClientIpResolver(0), objectMapper, now::get);
        for (int i = 0; i < 10; i++) {
            AtomicInteger reached = new AtomicInteger();
            disabled.filter(claim("10.0.0.9"), ex -> {
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
        ClaimIpRateLimitFilter concurrentFilter =
                new ClaimIpRateLimitFilter(true, limit, new ClientIpResolver(0), objectMapper, now::get);

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
                    MockServerWebExchange exchange = claim("10.9.9.9");
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

    // ------------------------------------------------------------------
    // TICKET-5 (F-7) : variantes percent-encodees du chemin (memes segments que
    // Spring Security / Gateway). Les requetes utilisent URI.create : le helper
    // MockServerHttpRequest.method(method, "<template>") reencoderait % en %25.
    // ------------------------------------------------------------------

    private static final String CANONICAL_PATH = "/api/v1/tontines/abc/members/claim";
    private static final String[] ENCODED_VARIANTS = {"/api/v1/tontines/abc/members/clai%6D", "/api/v1/tontines/abc/members/%63laim", "/api/v1/tontines/a%62c/members/claim"};

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
        String ip = "10.20.4.1";
        // Le quota est consomme alternativement par le chemin canonique et ses variantes encodees.
        for (int i = 0; i < IP_LIMIT; i++) {
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
        String ip = "10.20.4.2";
        for (int i = 0; i < IP_LIMIT; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.POST, ENCODED_VARIANTS[i % ENCODED_VARIANTS.length], ip))).isTrue();
        }
        assertThat(passesThrough(encodedRequest(HttpMethod.POST, CANONICAL_PATH, ip))).isFalse();
    }

    @Test
    void encodedNeighborPath_isNeverLimited() {
        for (int i = 0; i < IP_LIMIT + 5; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.POST, "/api/v1/tontines/abc/members/clai%6Dx", "10.20.4.4"))).isTrue();
        }
    }

    @Test
    void encodedVariant_withNonPostMethod_isNeverLimited() {
        for (int i = 0; i < IP_LIMIT + 5; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.GET, ENCODED_VARIANTS[0], "10.20.4.3"))).isTrue();
        }
    }
}
