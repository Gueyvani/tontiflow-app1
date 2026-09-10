package com.tontiflow.infrastructure.ratelimit;

import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * Résout l'adresse IP cliente d'un échange, pour le rate limiting IP du
 * Gateway (décision R21-C.A3.2, option O-B).
 *
 * <p><b>Modèle de confiance</b> — piloté par un unique entier
 * {@code trustedProxyCount} :</p>
 * <ul>
 *   <li>{@code <= 0} (défaut) : l'IP retenue est <b>uniquement</b> celle du
 *       pair TCP ({@link org.springframework.http.server.reactive.ServerHttpRequest#getRemoteAddress()}).
 *       L'en-tête {@code X-Forwarded-For} est <b>ignoré</b> — un client ne
 *       peut pas choisir l'IP sur laquelle il est limité.</li>
 *   <li>{@code > 0} : délègue à
 *       {@link XForwardedRemoteAddressResolver#maxTrustedIndex(int)} avec
 *       exactement ce nombre de proxies de confiance attendus devant le
 *       Gateway. Seuls les {@code k} derniers maillons de {@code X-Forwarded-For}
 *       sont pris en compte ; le préfixe fourni par le client est écarté.</li>
 * </ul>
 *
 * <p>{@link XForwardedRemoteAddressResolver#trustAll()} n'est <b>jamais</b>
 * utilisé : aucune confiance aveugle dans {@code X-Forwarded-For}.</p>
 */
public final class ClientIpResolver {

    private static final String UNKNOWN_IP = "unknown";

    private final RemoteAddressResolver delegate;

    /**
     * @param trustedProxyCount nombre de proxies de confiance devant le
     *                          Gateway. Toute valeur {@code <= 0} (y compris
     *                          négative, considérée comme invalide) retombe
     *                          sur le comportement sûr : pair TCP uniquement.
     */
    public ClientIpResolver(int trustedProxyCount) {
        this.delegate = trustedProxyCount > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyCount)
                : new RemoteAddressResolver() { };
    }

    /**
     * @return l'IP cliente sous forme littérale, ou {@code "unknown"} si elle
     *         ne peut pas être déterminée (jamais {@code null}).
     */
    public String resolve(ServerWebExchange exchange) {
        InetSocketAddress address = delegate.resolve(exchange);
        if (address == null) {
            return UNKNOWN_IP;
        }
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        String host = address.getHostString();
        return (host == null || host.isBlank()) ? UNKNOWN_IP : host;
    }
}
