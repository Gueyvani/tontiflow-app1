package com.tontiflow.infrastructure.ratelimit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tontiflow.core.dto.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Rate limiting <b>IP, local et in-JVM</b>, spécifique à la déconnexion
 * {@code POST /api/v1/auth/logout} (décision TICKET-4, constat F-1 de l'audit
 * post-TICKET-3). Aucun Redis, aucune dépendance externe, aucun stockage
 * persistant.
 *
 * <p>{@code /api/v1/auth/logout} est {@code permitAll} au Gateway (en POST
 * uniquement) comme dans {@code authentication-service} : aucun JWT d'accès
 * n'y est exigé — le refresh token présenté dans le corps est lui-même le
 * credential, ce qui permet de révoquer une session même après expiration de
 * l'Access Token. Étant public, il doit être borné : chaque appel effectue une
 * lecture indexée en base (et une écriture si le token est valide).</p>
 *
 * <p><b>Compteur indépendant</b> de {@link RefreshIpRateLimitFilter} (jamais
 * partagé) : des déconnexions en rafale derrière un même NAT ne doivent pas
 * consommer le budget de renouvellement de session. Ce filtre ne lit jamais le
 * corps de la requête et ne contient aucune logique JWT : l'IP (résolue par
 * {@link ClientIpResolver}) est la seule clé. Au dépassement : réponse
 * <b>429</b> avec en-tête {@code Retry-After} (1 à 60 s), corps
 * {@link ErrorResponse} générique, requête rejetée <b>avant</b> tout proxy vers
 * {@code authentication-service}.</p>
 *
 * <p>Limite (30/min par défaut) : voir {@code application.yml}. <b>Non
 * distribué</b> : compteurs par instance de Gateway, remis à zéro au
 * redémarrage — même limite assumée que les autres filtres de ce paquet.</p>
 *
 * <p>Ordonnancement : {@link Ordered#HIGHEST_PRECEDENCE}{@code + 100}, comme les
 * autres filtres (chemins disjoints : jamais deux filtres sur la même requête).
 * La {@code SecurityWebFilterChain} s'exécute de toute façon avant tout
 * {@link GlobalFilter}.</p>
 */
@Component
public class LogoutIpRateLimitFilter implements GlobalFilter, Ordered {

    private static final RequestPathMatcher LOGOUT_PATH =
            RequestPathMatcher.exactWithOptionalTrailingSlash("/api/v1/auth/logout");
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    /** Purge paresseuse : balaie la table toutes les N requêtes logout filtrées. */
    private static final int SWEEP_EVERY = 500;
    /** Une entrée sans accès depuis 10 minutes est éligible à la purge. */
    private static final long STALE_MILLIS = 10 * 60_000L;

    private final boolean enabled;
    private final int logoutPerMinute;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final LongSupplier clock;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    @Autowired
    public LogoutIpRateLimitFilter(
            @Value("${logout-ip-rate-limit.enabled:true}") boolean enabled,
            @Value("${logout-ip-rate-limit.logout-per-minute:30}") int logoutPerMinute,
            @Value("${logout-ip-rate-limit.trusted-proxy-count:0}") int trustedProxyCount,
            ObjectMapper objectMapper) {
        this(enabled, logoutPerMinute, new ClientIpResolver(trustedProxyCount), objectMapper, System::currentTimeMillis);
    }

    /** Constructeur de test : résolveur d'IP et horloge injectables pour des scénarios déterministes. */
    LogoutIpRateLimitFilter(boolean enabled, int logoutPerMinute, ClientIpResolver clientIpResolver,
                             ObjectMapper objectMapper, LongSupplier clock) {
        this.enabled = enabled;
        this.logoutPerMinute = logoutPerMinute;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!enabled || !isLogoutPost(request)) {
            return chain.filter(exchange);
        }

        long now = clock.getAsLong();
        Window window = windows.computeIfAbsent("ip:" + clientIpResolver.resolve(exchange), k -> new Window());

        long retryAfter = window.retryAfterSecondsIfExceeded(logoutPerMinute, now);
        if (retryAfter > 0L) {
            return writeTooManyRequests(exchange, retryAfter);
        }

        window.record(now);
        maybeSweep(now);
        return chain.filter(exchange);
    }

    private static boolean isLogoutPost(ServerHttpRequest request) {
        return HttpMethod.POST.equals(request.getMethod())
                && LOGOUT_PATH.matches(request);
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange, long retryAfterSeconds) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Trop de requêtes de déconnexion, réessayez plus tard.",
                exchange.getRequest().getURI().getRawPath(),
                resolveCorrelationId(exchange.getRequest()));

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            payload = ("{\"title\":\"Too Many Requests\",\"status\":429}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(payload);
        return response.writeWith(Mono.just(buffer));
    }

    private static String resolveCorrelationId(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
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
     * donnée (thread-safe via {@code synchronized}). Structurellement identique
     * à la classe interne homonyme des autres filtres de ce paquet : duplication
     * assumée, même précédent déjà documenté.
     */
    private static final class Window {

        private final Deque<Long> hits = new ArrayDeque<>();
        private long lastAccessMillis;

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
