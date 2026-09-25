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

class RegisterIpRateLimitFilterTest {

    private static final int REGISTER_LIMIT = 5;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RegisterIpRateLimitFilter filter =
            new RegisterIpRateLimitFilter(true, REGISTER_LIMIT, new ClientIpResolver(0), objectMapper, now::get);

    private static MockServerWebExchange register(String ip) {
        return request(HttpMethod.POST, "/api/v1/auth/register", ip);
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

    // 1 — requete normale (sous la limite).
    @Test
    void underRegisterLimit_requestPassesThrough() {
        for (int i = 0; i < REGISTER_LIMIT; i++) {
            MockServerWebExchange exchange = register("10.0.2.1");
            assertThat(passesThrough(exchange)).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    // 2/3 — limite atteinte, requete suivante -> 429.
    @Test
    void exceedingRegisterLimit_yields429_andChainNotInvoked() {
        for (int i = 0; i < REGISTER_LIMIT; i++) {
            assertThat(passesThrough(register("10.0.2.2"))).isTrue();
        }
        MockServerWebExchange exchange = register("10.0.2.2");
        boolean passed = passesThrough(exchange);

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // 4 — presence de Retry-After, valeur valide, corps generique.
    @Test
    void exceedingRegisterLimit_responseHasValidRetryAfter_andGenericBody() {
        for (int i = 0; i < REGISTER_LIMIT; i++) {
            passesThrough(register("10.0.2.3"));
        }
        MockServerWebExchange exchange = register("10.0.2.3");
        passesThrough(exchange);

        String retryAfter = exchange.getResponse().getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/json");

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("Trop de tentatives d'inscription");
    }

    // 5 — IP differente -> quota independant.
    @Test
    void differentIps_haveIndependentCounters() {
        for (int i = 0; i < REGISTER_LIMIT; i++) {
            passesThrough(register("10.0.2.4"));
        }
        assertThat(passesThrough(register("10.0.2.4"))).isFalse();
        assertThat(passesThrough(register("10.0.2.5"))).isTrue();
    }

    // 6 — autre chemin -> jamais affecte par ce filtre.
    @Test
    void loginPath_isNeverLimitedByThisFilter() {
        for (int i = 0; i < REGISTER_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/auth/login", "10.0.2.6"))).isTrue();
        }
    }

    // 7 — autre methode HTTP -> jamais limitee (filtre POST-specifique).
    @Test
    void nonPostMethodOnRegisterPath_isNeverLimited() {
        for (int i = 0; i < REGISTER_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/auth/register", "10.0.2.7"))).isTrue();
        }
    }

    // 8 — slash final : meme contrat que les filtres existants.
    @Test
    void trailingSlashRegisterPath_isLimited() {
        for (int i = 0; i < REGISTER_LIMIT; i++) {
            passesThrough(request(HttpMethod.POST, "/api/v1/auth/register/", "10.0.2.8"));
        }
        assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/auth/register/", "10.0.2.8"))).isFalse();
    }

    // 9 — configuration disabled -> jamais de limite.
    @Test
    void whenDisabled_neverLimits() {
        RegisterIpRateLimitFilter disabled =
                new RegisterIpRateLimitFilter(false, 1, new ClientIpResolver(0), objectMapper, now::get);
        for (int i = 0; i < 10; i++) {
            AtomicInteger reached = new AtomicInteger();
            disabled.filter(register("10.0.2.9"), ex -> {
                reached.incrementAndGet();
                return Mono.empty();
            }).block();
            assertThat(reached.get()).isEqualTo(1);
        }
    }

    @Test
    void afterWindowExpires_requestIsAcceptedAgain() {
        for (int i = 0; i < REGISTER_LIMIT; i++) {
            passesThrough(register("10.0.2.10"));
        }
        assertThat(passesThrough(register("10.0.2.10"))).isFalse();

        now.addAndGet(61_000L);

        assertThat(passesThrough(register("10.0.2.10"))).isTrue();
    }

    // Concurrence : meme patron que les 3 filtres existants - des requetes concurrentes
    // sur la meme IP ne peuvent pas contourner le seuil (tolerance identique, deja
    // documentee comme caracteristique connue du Sliding Window Log partage).
    @Test
    void concurrentRequestsOnSameIp_areThreadSafe_andBounded() throws Exception {
        int limit = 5;
        int threads = 40;
        RegisterIpRateLimitFilter concurrentFilter =
                new RegisterIpRateLimitFilter(true, limit, new ClientIpResolver(0), objectMapper, now::get);

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
                    MockServerWebExchange exchange = register("10.9.7.7");
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

    private static final String CANONICAL_PATH = "/api/v1/auth/register";
    private static final String[] ENCODED_VARIANTS = {"/api/v1/auth/regist%65r", "/api/v1/auth/%72egister"};

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
        String ip = "10.20.2.1";
        // Le quota est consomme alternativement par le chemin canonique et ses variantes encodees.
        for (int i = 0; i < REGISTER_LIMIT; i++) {
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
        String ip = "10.20.2.2";
        for (int i = 0; i < REGISTER_LIMIT; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.POST, ENCODED_VARIANTS[i % ENCODED_VARIANTS.length], ip))).isTrue();
        }
        assertThat(passesThrough(encodedRequest(HttpMethod.POST, CANONICAL_PATH, ip))).isFalse();
    }

    @Test
    void encodedNeighborPath_isNeverLimited() {
        for (int i = 0; i < REGISTER_LIMIT + 5; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.POST, "/api/v1/auth/regist%65rx", "10.20.2.4"))).isTrue();
        }
    }

    @Test
    void encodedVariant_withNonPostMethod_isNeverLimited() {
        for (int i = 0; i < REGISTER_LIMIT + 5; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.GET, ENCODED_VARIANTS[0], "10.20.2.3"))).isTrue();
        }
    }
}
