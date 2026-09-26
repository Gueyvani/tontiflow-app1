package com.tontiflow.infrastructure.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tontiflow.security.jwt.ServiceTokenCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de {@link ServiceTokenAuthenticationFilter} (décision F-8) : authentification par
 * portées, absence de réponse écrite par le filtre, et journalisation des rejets sans jamais exposer
 * le jeton, sa signature, ses claims ni le secret.
 */
class ServiceTokenAuthenticationFilterTest {

    private static final String SECRET = "filter-test-only-secret-0123456789abcdef";

    private final ServiceTokenCodec codec = new ServiceTokenCodec(SECRET, Clock.systemUTC());
    private final ServiceTokenAuthenticationFilter filter = new ServiceTokenAuthenticationFilter(codec);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(ServiceTokenAuthenticationFilter.class);

    @BeforeEach
    void attachAppender() {
        logs.start();
        filterLogger.addAppender(logs);
        filterLogger.setLevel(Level.INFO);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String method, String path, String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        return request;
    }

    private Authentication run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        Authentication[] seen = new Authentication[1];
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = SecurityContextHolder.getContext().getAuthentication();
            }
        });
        return seen[0];
    }

    @Test
    void validToken_authenticatesWithScopeAuthorities_andPrincipalIsTheService() throws Exception {
        String token = codec.issue(ServiceTokenCodec.SERVICE_TONTINE, ServiceTokenCodec.SERVICE_FINANCIAL,
                List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE, ServiceTokenCodec.SCOPE_LEDGER_READ), UUID.randomUUID());
        MockHttpServletResponse response = new MockHttpServletResponse();

        Authentication authentication = run(request("POST", "/internal/contributions", token), response);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo("service:tontine-service");
        assertThat(authentication.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("SCOPE_ledger.write", "SCOPE_ledger.read");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void invalidToken_leavesRequestUnauthenticated_writesNoResponse_andLogsWithoutTheToken() throws Exception {
        ServiceTokenCodec other = new ServiceTokenCodec("another-secret-another-secret-0123456789", Clock.systemUTC());
        String forged = other.issue(ServiceTokenCodec.SERVICE_TONTINE, ServiceTokenCodec.SERVICE_FINANCIAL,
                List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE), UUID.randomUUID());
        String signature = forged.substring(forged.lastIndexOf('.') + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Authentication authentication = run(request("POST", "/internal/contributions", forged), response);

        assertThat(authentication).isNull();
        assertThat(response.getStatus()).isEqualTo(200); // le filtre n'ecrit rien : la chaine repond 401
        assertThat(logs.list).hasSize(1);
        String message = logs.list.get(0).getFormattedMessage();
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(message).startsWith("service_token_rejected");
        assertThat(message).contains("POST").contains("/internal/contributions");
        assertThat(message).doesNotContain(forged).doesNotContain(signature)
                .doesNotContain("another-secret").doesNotContain(SECRET);
    }

    @Test
    void missingToken_isLoggedAsMissing_andLeavesRequestUnauthenticated() throws Exception {
        Authentication authentication = run(
                request("GET", "/internal/accounts/1/TONTINE/balance", null), new MockHttpServletResponse());

        assertThat(authentication).isNull();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("reason=MISSING");
    }

    @Test
    void userStyleTokenAndGarbage_areRejected_withoutLoggingTheirContent() throws Exception {
        String garbage = "not.a.valid-jwt-secret-looking-content";

        Authentication authentication = run(
                request("GET", "/internal/accounts/1/TONTINE/balance", garbage), new MockHttpServletResponse());

        assertThat(authentication).isNull();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).doesNotContain(garbage);
    }

    @Test
    void nonInternalPaths_areNotFiltered_evenWithAToken() throws Exception {
        String token = codec.issue(ServiceTokenCodec.SERVICE_TONTINE, ServiceTokenCodec.SERVICE_FINANCIAL,
                List.of(ServiceTokenCodec.SCOPE_LEDGER_READ), null);

        Authentication authentication = run(request("GET", "/actuator/health", token), new MockHttpServletResponse());

        assertThat(authentication).isNull();
        assertThat(logs.list).isEmpty();
    }
}
