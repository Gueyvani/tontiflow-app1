package com.tontiflow.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tontiflow.UserContext;
import com.tontiflow.core.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Rate limiting <b>local et in-JVM</b>, spécifique à l'unique endpoint
 * {@code POST /api/v1/tontines/{tontineId}/members/claim} (décisions
 * R21-A / R21-B, option B). Aucun Redis, aucune dépendance externe, aucun
 * stockage persistant.
 *
 * <p>Compteur à fenêtre glissante d'une minute, par compte authentifié
 * ({@code UserContext.userId()} du JWT — jamais une valeur du corps). Au
 * dépassement : réponse <b>429</b> avec en-tête {@code Retry-After}, corps
 * {@link ErrorResponse} générique — la requête est rejetée <b>avant</b>
 * d'atteindre le contrôleur (aucun accès à l'invitation, à son hash, au
 * repository ni à PostgreSQL).</p>
 *
 * <p>La dimension « par IP » a été déplacée dans {@code api-gateway}
 * ({@code ClaimIpRateLimitFilter}) : derrière le Gateway, {@code getRemoteAddr()}
 * y désignait le pair TCP du Gateway, potentiellement partagé entre
 * utilisateurs (constat R21-C.A3 ; décision R21-C.A3.2, option O-B).</p>
 *
 * <p><b>Non distribué</b> : les compteurs sont par instance et remis à zéro
 * au redémarrage. C'est un palier de durcissement (risque P2), pas une
 * protection distribuée — voir R21-A.</p>
 *
 * <p>Ordonnancement : {@link Ordered#LOWEST_PRECEDENCE} → le filtre s'exécute
 * <i>après</i> toute la chaîne Spring Security (authentification + autorisation
 * effectuées, {@code SecurityContext} peuplé). Un appel non authentifié reçoit
 * donc le 401 habituel sans jamais atteindre ce filtre : le rate limiter n'est
 * pas une couche d'authentification.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ClaimRateLimitFilter extends OncePerRequestFilter {

    private static final Pattern CLAIM_PATH =
            Pattern.compile("^/api/v1/tontines/[^/]+/members/claim/?$");
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    /** Purge paresseuse : balaie la table toutes les N requêtes filtrées. */
    private static final int SWEEP_EVERY = 500;
    /** Une entrée sans accès depuis 10 minutes est éligible à la purge. */
    private static final long STALE_MILLIS = 10 * 60_000L;

    private final int accountPerMinute;
    private final ObjectMapper objectMapper;
    private final LongSupplier clock;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    @Autowired
    public ClaimRateLimitFilter(
            @Value("${claim-rate-limit.account-per-minute:5}") int accountPerMinute,
            ObjectMapper objectMapper) {
        this(accountPerMinute, objectMapper, System::currentTimeMillis);
    }

    /** Constructeur de test : horloge injectable pour des scénarios déterministes. */
    ClaimRateLimitFilter(int accountPerMinute, ObjectMapper objectMapper, LongSupplier clock) {
        this.accountPerMinute = accountPerMinute;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && CLAIM_PATH.matcher(request.getRequestURI()).matches());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        long now = clock.getAsLong();

        String accountId = currentAccountId();
        if (accountId == null) {
            // Aucune identité authentifiée (cas théorique : ce filtre s'exécute
            // après la chaîne de sécurité) : rien à limiter par compte.
            filterChain.doFilter(request, response);
            return;
        }

        Window accountWindow = windows.computeIfAbsent("acct:" + accountId, k -> new Window());
        long retryAfter = accountWindow.retryAfterSecondsIfExceeded(accountPerMinute, now);

        if (retryAfter > 0L) {
            writeTooManyRequests(request, response, retryAfter);
            return;
        }

        accountWindow.record(now);
        maybeSweep(now);

        filterChain.doFilter(request, response);
    }

    private static String currentAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserContext userContext) {
            return userContext.userId().toString();
        }
        return null;
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Trop de tentatives, réessayez plus tard.",
                request.getRequestURI(),
                resolveCorrelationId(request));
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(CORRELATION_ID_HEADER);
        return (header == null || header.isBlank()) ? UUID.randomUUID().toString() : header;
    }

    private void maybeSweep(long now) {
        if (sinceSweep.incrementAndGet() < SWEEP_EVERY) {
            return;
        }
        sinceSweep.set(0);
        long staleBefore = now - STALE_MILLIS;
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
            if (it.next().lastAccessBefore(staleBefore)) {
                it.remove();
            }
        }
    }

    /**
     * Journal des horodatages d'accès sur la dernière minute, pour une clé
     * donnée. Toutes les méthodes sont {@code synchronized} : accès
     * thread-safe sans verrou pessimiste externe.
     */
    private static final class Window {

        private final Deque<Long> hits = new ArrayDeque<>();
        private long lastAccessMillis;

        /**
         * @return 0 si un nouvel appel est autorisé (sans l'enregistrer),
         *         sinon le nombre de secondes à attendre avant qu'un appel
         *         redevienne possible (au moins 1).
         */
        synchronized long retryAfterSecondsIfExceeded(int limitPerMinute, long nowMillis) {
            prune(nowMillis);
            if (hits.size() < limitPerMinute) {
                return 0L;
            }
            long freesAt = hits.peekFirst() + WINDOW_MILLIS;
            long retryMillis = freesAt - nowMillis;
            return Math.max(1L, (retryMillis + 999L) / 1000L);
        }

        synchronized void record(long nowMillis) {
            hits.addLast(nowMillis);
            lastAccessMillis = nowMillis;
        }

        synchronized boolean lastAccessBefore(long instantMillis) {
            return lastAccessMillis < instantMillis && hits.isEmpty();
        }

        private void prune(long nowMillis) {
            lastAccessMillis = nowMillis;
            long threshold = nowMillis - WINDOW_MILLIS;
            while (!hits.isEmpty() && hits.peekFirst() <= threshold) {
                hits.pollFirst();
            }
        }
    }
}
