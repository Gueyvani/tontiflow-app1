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
 * Rate limiting <b>IP, local et in-JVM</b>, spécifique aux endpoints
 * d'administration {@code /api/v1/admin/**} (décision R21-RC, Phase B —
 * constat issu de l'audit de suivi post-R21-D.9). Aucun Redis, aucune
 * dépendance externe, aucun stockage persistant.
 *
 * <p>Seul contrôleur concerné à ce jour : {@code RbacController}
 * (permissions, rôles, attribution de rôles aux comptes), déjà protégé par
 * {@code hasAuthority("ROLE_ADMIN")} côté {@code authentication-service} —
 * ce filtre n'ajoute qu'une limite de <b>débit</b>, il ne reproduit ni ne
 * remplace cette autorisation. {@code /api/v1/admin/**} n'étant pas
 * {@code permitAll} au Gateway, la {@code SecurityWebFilterChain}
 * (authentification JWT) s'exécute — comme pour tout {@link GlobalFilter} —
 * <b>avant</b> ce filtre : une requête sans JWT valide reçoit son 401 sans
 * jamais l'atteindre. Ce filtre ne voit donc que des requêtes déjà
 * authentifiées (JWT valide, rôle quelconque — le Gateway ne vérifie jamais
 * {@code ROLE_ADMIN} lui-même) ; un appelant authentifié mais non-admin
 * consomme donc le même quota qu'un admin légitime avant de recevoir un 403
 * en aval. Ce filtre ne transforme jamais un 401/403 en 429 : il s'exécute
 * strictement après l'authentification et n'intervient pas dans le chemin
 * d'autorisation RBAC.</p>
 *
 * <p><b>Différence structurelle avec {@link AuthIpRateLimitFilter}/
 * {@link ClaimIpRateLimitFilter}/{@link RefreshIpRateLimitFilter}/
 * {@link RegisterIpRateLimitFilter}</b> : ces quatre filtres ciblent chacun
 * un unique chemin fixe et la seule méthode {@code POST}. Ici, le périmètre
 * est un <b>préfixe</b> ({@code /api/v1/admin/**}) couvrant sept endpoints
 * (2 {@code GET}, 2 {@code POST}, 2 {@code PUT}, 1 {@code DELETE} au moment
 * de cette décision) avec segments de chemin variables
 * ({@code {roleId}}, {@code {permissionId}}, {@code {accountId}}) — <b>toutes
 * les méthodes HTTP</b> sont couvertes, sans restriction. Le quota est
 * <b>unique par IP</b> et partagé entre tous les endpoints d'administration
 * (pas un quota par endpoint) : ancrage sur le préfixe uniquement (PathPattern, TICKET-5).</p>
 *
 * <p>Fenêtre glissante d'une minute par IP source, résolue par
 * {@link ClientIpResolver} (pair TCP par défaut ; {@code X-Forwarded-For}
 * borné et opt-in seulement, jamais {@code trustAll()}). Une IP non
 * déterminable est résolue en la valeur littérale {@code "unknown"} par
 * {@link ClientIpResolver} (jamais {@code null}) : plusieurs requêtes sans
 * IP résolue partagent alors, par construction, la même clé de compteur et
 * donc le même quota — comportement hérité et volontairement inchangé,
 * identique à celui des quatre filtres existants.</p>
 *
 * <p>Au dépassement : réponse <b>429</b> avec en-tête {@code Retry-After}
 * (1 à 60 s), corps {@link ErrorResponse} générique — la requête est
 * rejetée <b>avant</b> tout proxy vers {@code authentication-service}.</p>
 *
 * <p><b>Atomicité check-and-record</b> : contrairement aux quatre filtres
 * existants (dont le contrôle et l'enregistrement sont deux appels
 * {@code synchronized} séparés — une fenêtre de course bornée y reste
 * possible, documentée et tolérée par leurs tests respectifs), ce filtre
 * fusionne les deux en une <b>unique</b> méthode {@code synchronized}
 * ({@link Window#recordIfAllowed}) : sous accès concurrent réel, jamais plus
 * de {@code adminPerMinute} enregistrements ne peuvent avoir lieu pour une
 * même IP dans la fenêtre — amélioration locale à ce seul filtre, sans
 * modification des quatre filtres existants (hors périmètre de cette
 * décision).</p>
 *
 * <p><b>Non distribué</b> : compteurs par instance de Gateway, remis à zéro
 * au redémarrage. Palier de durcissement, pas une protection distribuée —
 * même limite assumée que les quatre filtres existants. Sous plusieurs
 * instances de Gateway, la limite effective par IP peut atteindre
 * {@code N × adminPerMinute}.</p>
 *
 * <p>Ordonnancement : {@link Ordered#HIGHEST_PRECEDENCE}{@code + 100},
 * identique aux quatre filtres existants — après
 * {@code CorrelationIdGlobalFilter}, bien avant le proxy. Chemins
 * mutuellement exclusifs avec les quatre filtres existants : jamais deux
 * filtres actifs sur la même requête.</p>
 */
@Component
public class AdminIpRateLimitFilter implements GlobalFilter, Ordered {

    /**
     * PathPattern {@code /api/v1/admin/**} (chemin décodé segment par segment, comme Security) :
     * ancré sur le préfixe {@code /api/v1/admin} suivi soit de la fin de
     * chaîne, soit d'un {@code /} et de tout le reste (segments variables
     * inclus) — ne matche jamais un chemin dont le segment suivant
     * "admin" n'est pas exactement {@code /} ou la fin (ex. exclut
     * {@code /api/v1/administration}).
     */
    private static final RequestPathMatcher ADMIN_PATH_PREFIX = RequestPathMatcher.of("/api/v1/admin/**");
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    /** Purge paresseuse : balaie la table toutes les N requêtes admin filtrées. */
    private static final int SWEEP_EVERY = 500;
    /** Une entrée sans accès depuis 10 minutes est éligible à la purge. */
    private static final long STALE_MILLIS = 10 * 60_000L;

    private final boolean enabled;
    private final int adminPerMinute;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final LongSupplier clock;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    @Autowired
    public AdminIpRateLimitFilter(
            @Value("${admin-ip-rate-limit.enabled:true}") boolean enabled,
            @Value("${admin-ip-rate-limit.admin-per-minute:30}") int adminPerMinute,
            @Value("${admin-ip-rate-limit.trusted-proxy-count:0}") int trustedProxyCount,
            ObjectMapper objectMapper) {
        this(enabled, adminPerMinute, new ClientIpResolver(trustedProxyCount), objectMapper, System::currentTimeMillis);
    }

    /** Constructeur de test : résolveur d'IP et horloge injectables pour des scénarios déterministes. */
    AdminIpRateLimitFilter(boolean enabled, int adminPerMinute, ClientIpResolver clientIpResolver,
                            ObjectMapper objectMapper, LongSupplier clock) {
        this.enabled = enabled;
        this.adminPerMinute = adminPerMinute;
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
        if (!enabled || !isAdminPath(request)) {
            return chain.filter(exchange);
        }

        long now = clock.getAsLong();
        Window window = windows.computeIfAbsent("ip:" + clientIpResolver.resolve(exchange), k -> new Window());

        // Verification et enregistrement atomiques (voir javadoc de classe) : contrairement
        // aux quatre filtres existants, un seul appel synchronized couvre les deux etapes -
        // aucune fenetre de course entre le controle et l'enregistrement.
        long retryAfter = window.recordIfAllowed(adminPerMinute, now);
        if (retryAfter > 0L) {
            return writeTooManyRequests(exchange, retryAfter);
        }

        maybeSweep(now);
        return chain.filter(exchange);
    }

    private static boolean isAdminPath(ServerHttpRequest request) {
        // Aucune restriction de methode HTTP : toutes (GET/POST/PUT/DELETE/...) sont
        // couvertes des lors que le chemin correspond au prefixe d'administration
        // (decision R21-RC, Phase B, Decision 1).
        return ADMIN_PATH_PREFIX.matches(request);
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange, long retryAfterSeconds) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Trop de tentatives d'administration, réessayez plus tard.",
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
     * <p>À la différence de la classe interne homonyme des quatre filtres
     * existants, {@link #recordIfAllowed} fusionne contrôle et
     * enregistrement en une seule opération atomique (voir javadoc de
     * classe) — pas de duplication du reste de la structure au-delà de
     * cette fusion, qui reste minimale.</p>
     */
    private static final class Window {

        private final Deque<Long> hits = new ArrayDeque<>();
        private long lastAccessMillis;

        /**
         * Vérifie ET enregistre atomiquement (un seul bloc {@code synchronized}).
         *
         * @return {@code 0} si la requête est acceptée (déjà comptabilisée) ;
         *         sinon le nombre de secondes à attendre avant qu'un nouvel
         *         appel puisse être accepté (au moins 1).
         */
        synchronized long recordIfAllowed(int limitPerMinute, long nowMillis) {
            prune(nowMillis);
            if (hits.size() < limitPerMinute) {
                hits.addLast(nowMillis);
                lastAccessMillis = nowMillis;
                return 0L;
            }
            long freesAt = hits.peekFirst() + WINDOW_MILLIS;
            long retryMillis = freesAt - nowMillis;
            return Math.max(1L, (retryMillis + 999L) / 1000L);
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
