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
import java.util.regex.Pattern;

/**
 * Rate limiting <b>IP, local et in-JVM</b>, spécifique à l'endpoint de
 * renouvellement de session {@code POST /api/v1/auth/refresh} (décision
 * R21-D.7, Phase B — constat D4-03/R21-D.4). Aucun Redis, aucune dépendance
 * externe, aucun stockage persistant.
 *
 * <p>{@code /api/v1/auth/refresh} est {@code permitAll} au Gateway comme dans
 * {@code authentication-service} : aucun JWT d'accès n'y est exigé (le
 * refresh token présenté est lui-même le credential), et il n'était
 * rate-limité nulle part. Contrairement à {@code /auth/login}, l'IP est ici
 * la <b>seule</b> dimension exploitable au Gateway : le token brut présenté
 * n'est ni décodable ni résolvable en {@code accountId} sans dupliquer la
 * logique de {@code RefreshTokenRepository.findByTokenHash} au Gateway
 * (décision R21-D.7, Phase B, §3).</p>
 *
 * <p>Fenêtre glissante d'une minute par IP source, résolue par
 * {@link ClientIpResolver} (pair TCP par défaut ; {@code X-Forwarded-For}
 * borné et opt-in seulement, jamais {@code trustAll()}). Au dépassement :
 * réponse <b>429</b> avec en-tête {@code Retry-After} (1 à 60 s), corps
 * {@link ErrorResponse} générique — la requête est rejetée <b>avant</b> tout
 * proxy vers {@code authentication-service}. La réponse ne révèle jamais la
 * validité, l'existence, l'expiration ou la révocation du token présenté :
 * seul le fait du dépassement de débit est signalé.</p>
 *
 * <p>Limite volontairement distincte de celle de {@code /auth/login}
 * ({@link AuthIpRateLimitFilter}) : {@code /refresh} est appelé
 * automatiquement par tout client à chaque expiration d'access token
 * (potentiellement par plusieurs appareils/onglets pour un même utilisateur
 * légitime), un rythme structurellement plus élevé qu'un login humain
 * ponctuel — décision R21-D.7, Phase B, §5/§6.</p>
 *
 * <p><b>Non distribué</b> : compteurs par instance de Gateway, remis à zéro
 * au redémarrage. Palier de durcissement, pas une protection distribuée —
 * même limite assumée que {@link AuthIpRateLimitFilter} et
 * {@link ClaimIpRateLimitFilter}.</p>
 *
 * <p>Ordonnancement : {@link Ordered#HIGHEST_PRECEDENCE}{@code + 100} → après
 * {@code CorrelationIdGlobalFilter}, bien avant le proxy. La
 * {@code SecurityWebFilterChain} du Gateway s'exécute de toute façon avant
 * tout {@link GlobalFilter}. Ce filtre, {@code AuthIpRateLimitFilter} et
 * {@code ClaimIpRateLimitFilter} ne s'appliquent jamais à la même requête
 * (chemins disjoints).</p>
 */
@Component
public class RefreshIpRateLimitFilter implements GlobalFilter, Ordered {

    private static final Pattern REFRESH_PATH = Pattern.compile("^/api/v1/auth/refresh/?$");
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    /** Purge paresseuse : balaie la table toutes les N requêtes refresh filtrées. */
    private static final int SWEEP_EVERY = 500;
    /** Une entrée sans accès depuis 10 minutes est éligible à la purge. */
    private static final long STALE_MILLIS = 10 * 60_000L;

    private final boolean enabled;
    private final int refreshPerMinute;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final LongSupplier clock;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    @Autowired
    public RefreshIpRateLimitFilter(
            @Value("${refresh-ip-rate-limit.enabled:true}") boolean enabled,
            @Value("${refresh-ip-rate-limit.refresh-per-minute:30}") int refreshPerMinute,
            @Value("${refresh-ip-rate-limit.trusted-proxy-count:0}") int trustedProxyCount,
            ObjectMapper objectMapper) {
        this(enabled, refreshPerMinute, new ClientIpResolver(trustedProxyCount), objectMapper, System::currentTimeMillis);
    }

    /** Constructeur de test : résolveur d'IP et horloge injectables pour des scénarios déterministes. */
    RefreshIpRateLimitFilter(boolean enabled, int refreshPerMinute, ClientIpResolver clientIpResolver,
                              ObjectMapper objectMapper, LongSupplier clock) {
        this.enabled = enabled;
        this.refreshPerMinute = refreshPerMinute;
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
        if (!enabled || !isRefreshPost(request)) {
            return chain.filter(exchange);
        }

        long now = clock.getAsLong();
        Window window = windows.computeIfAbsent("ip:" + clientIpResolver.resolve(exchange), k -> new Window());

        long retryAfter = window.retryAfterSecondsIfExceeded(refreshPerMinute, now);
        if (retryAfter > 0L) {
            return writeTooManyRequests(exchange, retryAfter);
        }

        window.record(now);
        maybeSweep(now);
        return chain.filter(exchange);
    }

    private static boolean isRefreshPost(ServerHttpRequest request) {
        return HttpMethod.POST.equals(request.getMethod())
                && REFRESH_PATH.matcher(request.getURI().getRawPath()).matches();
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange, long retryAfterSeconds) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Trop de tentatives de renouvellement, réessayez plus tard.",
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
     * donnée. Toutes les méthodes sont {@code synchronized} : accès
     * thread-safe sans verrou pessimiste externe.
     *
     * <p>Structurellement identique à la classe interne homonyme de
     * {@code AuthIpRateLimitFilter}/{@code ClaimIpRateLimitFilter} : la
     * duplication (~40 lignes) est assumée à ce stade, même précédent déjà
     * documenté sur ces deux classes (une factorisation éventuelle
     * relèverait d'une décision distincte, hors scope de R21-D.7).</p>
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
