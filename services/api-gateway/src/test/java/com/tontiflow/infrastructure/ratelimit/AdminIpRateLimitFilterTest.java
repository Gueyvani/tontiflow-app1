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

/**
 * Tests de {@link AdminIpRateLimitFilter} (décision R21-RC, Phase C).
 *
 * <p>Contrairement aux filtres existants (chemin unique, POST uniquement),
 * ce filtre couvre un préfixe multi-endpoints et toutes les méthodes HTTP —
 * les scénarios ci-dessous reflètent explicitement cette différence
 * (partage de quota entre endpoints, couverture GET/PUT/DELETE).</p>
 */
class AdminIpRateLimitFilterTest {

    private static final int ADMIN_LIMIT = 30;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AdminIpRateLimitFilter filter =
            new AdminIpRateLimitFilter(true, ADMIN_LIMIT, new ClientIpResolver(0), objectMapper, now::get);

    private static MockServerWebExchange request(HttpMethod method, String path, String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path)
                        .remoteAddress(new InetSocketAddress(ip, 40000))
                        .build());
    }

    private static MockServerWebExchange requestWithXff(HttpMethod method, String path, String peerIp, String xffIp) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path)
                        .header("X-Forwarded-For", xffIp)
                        .remoteAddress(new InetSocketAddress(peerIp, 40000))
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

    // ------------------------------------------------------------------
    // A. Perimetre des routes
    // ------------------------------------------------------------------

    @Test
    void allSevenAdminEndpoints_areCoveredByTheSameQuota() {
        // Les 7 endpoints reels identifies en Phase B, methodes GET/POST/PUT/DELETE
        // melangees - un seul quota partage, pas un quota par endpoint.
        String ip = "10.1.1.1";
        assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/admin/permissions", ip))).isTrue();
        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/permissions", ip))).isTrue();
        assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/admin/roles", ip))).isTrue();
        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", ip))).isTrue();
        assertThat(passesThrough(request(HttpMethod.PUT,
                "/api/v1/admin/roles/33333333-3333-3333-3333-333333333333/permissions/44444444-4444-4444-4444-444444444444", ip))).isTrue();
        assertThat(passesThrough(request(HttpMethod.PUT,
                "/api/v1/admin/accounts/11111111-1111-1111-1111-111111111111/roles/33333333-3333-3333-3333-333333333333", ip))).isTrue();
        assertThat(passesThrough(request(HttpMethod.DELETE,
                "/api/v1/admin/accounts/11111111-1111-1111-1111-111111111111/roles/33333333-3333-3333-3333-333333333333", ip))).isTrue();
    }

    @Test
    void trailingSlashAdminPath_isCovered() {
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles/", "10.1.1.2"));
        }
        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles/", "10.1.1.2"))).isFalse();
    }

    @Test
    void bareAdminPrefix_isAlsoCovered() {
        // /api/v1/admin (sans suite) doit correspondre au prefixe, meme si aucun
        // endpoint reel n'y repond - coherent avec l'ancrage regex documente.
        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin", "10.1.1.3"))).isTrue();
    }

    @Test
    void lookAlikePath_isNotMatchedByPrefix() {
        // /api/v1/administration ne doit jamais etre traite comme un sous-chemin
        // de /api/v1/admin (l'ancrage exige un "/" ou la fin de chaine apres "admin").
        for (int i = 0; i < ADMIN_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/administration/anything", "10.1.1.4"))).isTrue();
        }
    }

    @Test
    void nonAdminRoutes_areNeverLimitedByThisFilter() {
        for (int i = 0; i < ADMIN_LIMIT + 5; i++) {
            assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/auth/login", "10.1.1.5"))).isTrue();
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/users/me", "10.1.1.5"))).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // B. Quota
    // ------------------------------------------------------------------

    @Test
    void thirtyRequests_areAllowed_thirtyFirst_isBlocked() {
        String ip = "10.1.2.1";
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", ip))).isTrue();
        }
        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", ip))).isFalse();
    }

    @Test
    void afterWindowExpires_quotaIsRenewed() {
        String ip = "10.1.2.2";
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", ip));
        }
        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", ip))).isFalse();

        now.addAndGet(61_000L);

        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", ip))).isTrue();
    }

    @Test
    void differentIps_haveIndependentQuotas() {
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", "10.1.2.3"));
        }
        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", "10.1.2.3"))).isFalse();
        assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", "10.1.2.4"))).isTrue();
    }

    @Test
    void quotaIsShared_acrossDifferentAdminEndpoints_forTheSameIp() {
        // Decision R21-RC : un seul quota par IP, pas un quota par endpoint - 15
        // requetes vers /permissions puis 15 vers /roles epuisent le meme quota de 30.
        String ip = "10.1.2.5";
        for (int i = 0; i < 15; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/permissions", ip))).isTrue();
        }
        for (int i = 0; i < 15; i++) {
            assertThat(passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", ip))).isTrue();
        }
        // La 31e requete globale (peu importe l'endpoint) est bloquee.
        assertThat(passesThrough(request(HttpMethod.POST, "/api/v1/admin/permissions", ip))).isFalse();
    }

    // ------------------------------------------------------------------
    // C. Reponse HTTP
    // ------------------------------------------------------------------

    @Test
    void underQuota_neverYields429() {
        String ip = "10.1.3.1";
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            MockServerWebExchange exchange = request(HttpMethod.GET, "/api/v1/admin/roles", ip);
            assertThat(passesThrough(exchange)).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void exceedingQuota_yields429_withValidRetryAfter_andGenericBody() {
        String ip = "10.1.3.2";
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            passesThrough(request(HttpMethod.GET, "/api/v1/admin/roles", ip));
        }
        MockServerWebExchange exchange = request(HttpMethod.GET, "/api/v1/admin/roles", ip);
        boolean passed = passesThrough(exchange);

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        String retryAfter = exchange.getResponse().getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/json");

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("Trop de tentatives d'administration");
        // Aucune fuite du statut d'autorisation (admin/non-admin) : le corps ne doit jamais
        // mentionner ROLE_ADMIN. Le chemin de la requete (ex. ".../roles") est en revanche
        // legitimement echo dans "instance", au meme titre que pour les quatre autres filtres -
        // ce n'est pas une fuite, seulement la route appelee.
        assertThat(body).doesNotContain("ROLE_ADMIN");
    }

    // ------------------------------------------------------------------
    // D. Resolution IP et securite
    // ------------------------------------------------------------------

    @Test
    void xForwardedFor_isIgnoredByDefault_trustedProxyCountZero() {
        // trustedProxyCount=0 (defaut) : X-Forwarded-For n'est jamais consulte -
        // deux requetes du meme pair TCP partagent le meme quota, meme avec des
        // valeurs XFF differentes (aucune confiance directe ajoutee a cet en-tete).
        String peerIp = "10.1.4.1";
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            passesThrough(requestWithXff(HttpMethod.GET, "/api/v1/admin/roles", peerIp, "203.0.113." + i));
        }
        assertThat(passesThrough(requestWithXff(HttpMethod.GET, "/api/v1/admin/roles", peerIp, "203.0.113.99"))).isFalse();
    }

    @Test
    void unresolvableIp_sharesTheSameUnknownQuota() {
        // ClientIpResolver renvoie "unknown" (jamais null) quand l'IP est indeterminable -
        // ici simule via une requete sans remoteAddress : toutes ces requetes partagent
        // la meme cle de compteur ("ip:unknown"), comportement herite et documente.
        MockServerWebExchange exchange1 = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/admin/roles").build());
        for (int i = 0; i < ADMIN_LIMIT - 1; i++) {
            MockServerWebExchange e = MockServerWebExchange.from(
                    MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/admin/roles").build());
            assertThat(passesThrough(e)).isTrue();
        }
        assertThat(passesThrough(exchange1)).isTrue(); // 30e, encore acceptee
        MockServerWebExchange exchange2 = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/admin/roles").build());
        assertThat(passesThrough(exchange2)).isFalse(); // 31e, partage le meme quota "unknown"
    }

    @Test
    void doesNotOverride401Or403_setByDownstreamSecurity_whenUnderQuota() {
        // Le filtre ne doit jamais transformer/ecraser un 401/403 emis par la chaine de
        // securite en aval - il se contente de deleguer quand le quota n'est pas depasse.
        MockServerWebExchange exchange = request(HttpMethod.GET, "/api/v1/admin/roles", "10.1.4.2");
        GatewayFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void whenDisabled_neverLimits() {
        AdminIpRateLimitFilter disabled =
                new AdminIpRateLimitFilter(false, 1, new ClientIpResolver(0), objectMapper, now::get);
        for (int i = 0; i < 10; i++) {
            AtomicInteger reached = new AtomicInteger();
            disabled.filter(request(HttpMethod.GET, "/api/v1/admin/roles", "10.1.4.3"), ex -> {
                reached.incrementAndGet();
                return Mono.empty();
            }).block();
            assertThat(reached.get()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // E. Concurrence
    // ------------------------------------------------------------------

    @Test
    void concurrentRequestsOnSameIp_neverExceedTheLimit_exactBound() throws Exception {
        // A la difference des quatre filtres existants (tolerance isBetween(limit, limit+10),
        // controle et enregistrement non atomiques ensemble), ce filtre fusionne les deux en
        // un seul synchronized (Window.recordIfAllowed) : sous concurrence reelle, jamais
        // plus de `limit` acceptations - borne EXACTE, pas une plage toleree. Si ce resultat
        // devait un jour deriver, il doit etre rapporte tel quel, pas masque.
        int limit = 30;
        int threads = 60;
        AdminIpRateLimitFilter concurrentFilter =
                new AdminIpRateLimitFilter(true, limit, new ClientIpResolver(0), objectMapper, now::get);

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
                    MockServerWebExchange exchange = request(HttpMethod.GET, "/api/v1/admin/roles", "10.1.5.1");
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
        // Borne exacte (pas de tolerance) : c'est precisement ce que l'atomicite
        // check-and-record de Window.recordIfAllowed garantit.
        assertThat(allowed.get()).isEqualTo(limit);
        assertThat(rejected.get()).isEqualTo(threads - limit);
    }

    // ------------------------------------------------------------------
    // TICKET-5 (F-7) : variantes percent-encodees du chemin (memes segments que
    // Spring Security / Gateway). Les requetes utilisent URI.create : le helper
    // MockServerHttpRequest.method(method, "<template>") reencoderait % en %25.
    // ------------------------------------------------------------------

    private static final String CANONICAL_PATH = "/api/v1/admin/roles";
    private static final String[] ENCODED_VARIANTS = {"/api/v1/adm%69n/roles", "/api/v1/%61dmin/roles", "/api/v1/admin/%72oles", "/api/v1/adm%69n"};

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
        String ip = "10.20.5.1";
        // Le quota est consomme alternativement par le chemin canonique et ses variantes encodees.
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            String path = (i % (ENCODED_VARIANTS.length + 1) == 0)
                    ? CANONICAL_PATH : ENCODED_VARIANTS[(i - 1) % ENCODED_VARIANTS.length];
            assertThat(passesThrough(encodedRequest(HttpMethod.GET, path, ip))).as(path).isTrue();
        }

        // Quota epuise : le canonique ET chaque variante sont refuses, avec le meme compteur.
        assertThat(passesThrough(encodedRequest(HttpMethod.GET, CANONICAL_PATH, ip))).isFalse();
        for (String variant : ENCODED_VARIANTS) {
            MockServerWebExchange exchange = encodedRequest(HttpMethod.GET, variant, ip);
            assertThat(passesThrough(exchange)).as(variant).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        // Avec slash final, toujours le meme compteur (contrat historique conserve).
        assertThat(passesThrough(encodedRequest(HttpMethod.GET, ENCODED_VARIANTS[0] + "/", ip))).isFalse();
    }

    @Test
    void encodedVariantsAlone_exhaustTheQuota_withoutAnyCanonicalRequest() {
        String ip = "10.20.5.2";
        for (int i = 0; i < ADMIN_LIMIT; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.GET, ENCODED_VARIANTS[i % ENCODED_VARIANTS.length], ip))).isTrue();
        }
        assertThat(passesThrough(encodedRequest(HttpMethod.GET, CANONICAL_PATH, ip))).isFalse();
    }

    @Test
    void encodedNeighborPath_isNeverLimited() {
        for (int i = 0; i < ADMIN_LIMIT + 5; i++) {
            assertThat(passesThrough(encodedRequest(HttpMethod.GET, "/api/v1/adm%69nistration/x", "10.20.5.4"))).isTrue();
        }
    }
}
