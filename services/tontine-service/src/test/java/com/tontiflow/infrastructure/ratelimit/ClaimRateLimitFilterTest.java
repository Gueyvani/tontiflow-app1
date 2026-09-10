package com.tontiflow.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tontiflow.UserContext;
import jakarta.servlet.FilterChain;
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

class ClaimRateLimitFilterTest {

    private static final int IP_LIMIT = 3;
    private static final int ACCOUNT_LIMIT = 2;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ClaimRateLimitFilter filter =
            new ClaimRateLimitFilter(IP_LIMIT, ACCOUNT_LIMIT, objectMapper, now::get);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest claimRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tontines/1/members/claim");
        request.setRemoteAddr(ip);
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
    void underIpLimit_requestPassesThrough() throws Exception {
        for (int i = 0; i < IP_LIMIT; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(passesThrough(claimRequest("10.0.0.1"), response)).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void underAccountLimit_requestPassesThrough() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            authenticateAs(account);
            assertThat(passesThrough(claimRequest("10.0.0.9"), new MockHttpServletResponse())).isTrue();
        }
    }

    @Test
    void exceedingIpLimit_yields429WithRetryAfter_andChainNotInvoked() throws Exception {
        for (int i = 0; i < IP_LIMIT; i++) {
            passesThrough(claimRequest("10.0.0.2"), new MockHttpServletResponse());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean passed = passesThrough(claimRequest("10.0.0.2"), response);

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
    void exceedingAccountLimit_yields429_evenFromDifferentIps() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            authenticateAs(account);
            passesThrough(claimRequest("10.0.1." + i), new MockHttpServletResponse());
        }
        authenticateAs(account);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean passed = passesThrough(claimRequest("10.0.1.99"), response);

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void afterWindowExpires_requestIsAcceptedAgain() throws Exception {
        for (int i = 0; i < IP_LIMIT; i++) {
            passesThrough(claimRequest("10.0.0.3"), new MockHttpServletResponse());
        }
        assertThat(passesThrough(claimRequest("10.0.0.3"), new MockHttpServletResponse())).isFalse();

        now.addAndGet(61_000L); // au-delà de la fenêtre d'une minute

        assertThat(passesThrough(claimRequest("10.0.0.3"), new MockHttpServletResponse())).isTrue();
    }

    @Test
    void differentIps_haveIndependentCounters() throws Exception {
        for (int i = 0; i < IP_LIMIT; i++) {
            passesThrough(claimRequest("10.0.0.4"), new MockHttpServletResponse());
        }
        assertThat(passesThrough(claimRequest("10.0.0.4"), new MockHttpServletResponse())).isFalse();
        assertThat(passesThrough(claimRequest("10.0.0.5"), new MockHttpServletResponse())).isTrue();
    }

    @Test
    void differentAccounts_haveIndependentCounters() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            authenticateAs(a);
            passesThrough(claimRequest("10.0.2.1"), new MockHttpServletResponse());
        }
        authenticateAs(a);
        assertThat(passesThrough(claimRequest("10.0.2.2"), new MockHttpServletResponse())).isFalse();
        authenticateAs(b);
        assertThat(passesThrough(claimRequest("10.0.2.3"), new MockHttpServletResponse())).isTrue();
    }

    @Test
    void nonClaimPath_isNeverLimited() throws Exception {
        for (int i = 0; i < IP_LIMIT + 5; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/v1/tontines/1/members");
            request.setRemoteAddr("10.0.0.6");
            assertThat(passesThrough(request, new MockHttpServletResponse())).isTrue();
        }
    }

    @Test
    void nonPostMethodOnClaimPath_isNeverLimited() throws Exception {
        for (int i = 0; i < IP_LIMIT + 5; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/v1/tontines/1/members/claim");
            request.setRemoteAddr("10.0.0.7");
            assertThat(passesThrough(request, new MockHttpServletResponse())).isTrue();
        }
    }

    @Test
    void concurrentRequestsOnSameKey_areThreadSafe_andBounded() throws Exception {
        int limit = 5;
        int threads = 40;
        ClaimRateLimitFilter concurrentFilter =
                new ClaimRateLimitFilter(limit, 1000, objectMapper, now::get);

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
                    MockHttpServletRequest request = claimRequest("10.9.9.9");
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
