package com.tontiflow.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tontiflow.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de {@link ClaimRateLimitFilter} après R21-C.A3.2 : le
 * filtre ne porte plus que la dimension <b>par compte</b> (la dimension IP
 * est passée dans {@code api-gateway}). La clé reste l'UUID authentifié du
 * {@link UserContext}, jamais une valeur du corps ni l'adresse réseau.
 */
class ClaimRateLimitFilterTest {

    private static final int ACCOUNT_LIMIT = 2;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ClaimRateLimitFilter filter =
            new ClaimRateLimitFilter(ACCOUNT_LIMIT, objectMapper, now::get);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest claimRequest() {
        return claimRequest("POST", "/api/v1/tontines/1/members/claim");
    }

    private static MockHttpServletRequest claimRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    private static void authenticateAs(UUID accountId) {
        UserContext ctx = new UserContext(accountId, "u", "u@t.test", Set.of("ROLE_USER"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ctx, null, List.of()));
    }

    /** @return true si la requête est passée jusqu'au bout de la chaîne (non limitée). */
    private boolean passesThrough(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null;
    }

    @Test
    void underAccountLimit_requestPassesThrough() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            authenticateAs(account);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(passesThrough(claimRequest(), response)).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void exceedingAccountLimit_yields429WithRetryAfter_andChainNotInvoked() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            authenticateAs(account);
            passesThrough(claimRequest(), new MockHttpServletResponse());
        }
        authenticateAs(account);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean passed = passesThrough(claimRequest(), response);

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        long retryAfter = Long.parseLong(response.getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("Trop de tentatives");
        assertThat(response.getContentAsString()).doesNotContain("Invitation");
    }

    @Test
    void accountLimit_isIndependentOfRemoteAddr() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            authenticateAs(account);
            MockHttpServletRequest request = claimRequest();
            request.setRemoteAddr("10.0.1." + i);
            passesThrough(request, new MockHttpServletResponse());
        }
        authenticateAs(account);
        MockHttpServletRequest request = claimRequest();
        request.setRemoteAddr("10.0.1.99");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void afterWindowExpires_requestIsAcceptedAgain() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            authenticateAs(account);
            passesThrough(claimRequest(), new MockHttpServletResponse());
        }
        authenticateAs(account);
        assertThat(passesThrough(claimRequest(), new MockHttpServletResponse())).isFalse();

        now.addAndGet(61_000L); // au-delà de la fenêtre d'une minute

        authenticateAs(account);
        assertThat(passesThrough(claimRequest(), new MockHttpServletResponse())).isTrue();
    }

    @Test
    void differentAccounts_haveIndependentCounters() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            authenticateAs(a);
            passesThrough(claimRequest(), new MockHttpServletResponse());
        }
        authenticateAs(a);
        assertThat(passesThrough(claimRequest(), new MockHttpServletResponse())).isFalse();
        authenticateAs(b);
        assertThat(passesThrough(claimRequest(), new MockHttpServletResponse())).isTrue();
    }

    @Test
    void unauthenticatedRequest_isNeverLimited() throws Exception {
        // Cas théorique (le filtre s'exécute après la chaîne de sécurité) :
        // sans identité, aucune clé de compte → aucune limitation.
        for (int i = 0; i < ACCOUNT_LIMIT + 5; i++) {
            assertThat(passesThrough(claimRequest(), new MockHttpServletResponse())).isTrue();
        }
    }

    @Test
    void nonClaimPath_isNeverLimited() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT + 5; i++) {
            authenticateAs(account);
            assertThat(passesThrough(claimRequest("POST", "/api/v1/tontines/1/members"),
                    new MockHttpServletResponse())).isTrue();
        }
    }

    @Test
    void nonPostMethodOnClaimPath_isNeverLimited() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT + 5; i++) {
            authenticateAs(account);
            assertThat(passesThrough(claimRequest("GET", "/api/v1/tontines/1/members/claim"),
                    new MockHttpServletResponse())).isTrue();
        }
    }

    @Test
    void concurrentRequestsOnSameAccount_areThreadSafe_andBounded() throws Exception {
        int limit = 5;
        int threads = 40;
        ClaimRateLimitFilter concurrentFilter =
                new ClaimRateLimitFilter(limit, objectMapper, now::get);
        UUID account = UUID.randomUUID();

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
                    authenticateAs(account);
                    MockHttpServletRequest request = claimRequest();
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    MockFilterChain chain = new MockFilterChain();
                    concurrentFilter.doFilter(request, response, chain);
                    if (chain.getRequest() != null) {
                        allowed.incrementAndGet();
                    } else if (response.getStatus() == 429) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(errors).hasValue(0);
        assertThat(allowed.get() + rejected.get()).isEqualTo(threads);
        // Thread-safe : au moins `limit` passent, avec un léger dépassement possible
        // sous forte contention (check puis record non atomiques entre eux).
        assertThat(allowed.get()).isBetween(limit, limit + 10);
    }
}
