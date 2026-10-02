package com.tontiflow.infrastructure.ratelimit;

import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.web.server.ServerWebExchange;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

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
 *
 * <p><b>Normalisation IPv6 par préfixe (décision N-2)</b> : une fois
 * l'adresse résolue par le modèle de confiance ci-dessus (jamais avant —
 * {@link #resolve} applique la troncature <i>après</i> {@code delegate.resolve(exchange)}),
 * une véritable {@link Inet6Address} est tronquée au préfixe réseau
 * {@link #ipv6PrefixLength} (défaut {@link #DEFAULT_IPV6_PREFIX_LENGTH} bits
 * — unité d'allocation standard par abonné final, RFC 6177) avant de devenir
 * la clé de rate limiting : deux adresses IPv6 d'un même préfixe produisent
 * ainsi la même clé, empêchant qu'une rotation d'adresse au sein d'un même
 * bloc délégué (ex. {@code /64} routé à un seul abonné) ne multiplie le
 * quota effectif. La troncature opère sur les octets de l'adresse ({@link
 * Inet6Address#getAddress()}), jamais sur sa représentation textuelle
 * (compressée ou non) — élimine tout risque de clé divergente pour une même
 * adresse selon sa forme d'écriture d'origine. IPv4 n'est jamais tronquée
 * (une allocation IPv4 complète par client reste rare et coûteuse, le
 * problème N-2 ne s'y applique pas).</p>
 *
 * <p><b>Adresses IPv4-mappées IPv6</b> (ex. {@code ::ffff:192.168.1.10}) :
 * vérifié empiriquement (JDK 21, cette base de code — parsing textuel,
 * construction depuis les octets bruts, et une vraie connexion socket
 * locale) qu'elles sont <b>toujours</b> exposées par la JVM comme {@link
 * java.net.Inet4Address}, jamais comme {@link Inet6Address} — aucun
 * traitement spécial n'est donc nécessaire ici : le chemin IPv4 existant
 * (adresse littérale inchangée) s'applique de façon transparente.</p>
 */
public final class ClientIpResolver {

    /** Unité d'allocation IPv6 standard par abonné final (RFC 6177) — valeur par défaut, pas arbitraire. */
    public static final int DEFAULT_IPV6_PREFIX_LENGTH = 64;
    private static final int MIN_IPV6_PREFIX_LENGTH = 1;
    private static final int MAX_IPV6_PREFIX_LENGTH = 128;

    private static final String UNKNOWN_IP = "unknown";

    private final RemoteAddressResolver delegate;
    private final int ipv6PrefixLength;

    /**
     * @param trustedProxyCount nombre de proxies de confiance devant le
     *                          Gateway. Toute valeur {@code <= 0} (y compris
     *                          négative, considérée comme invalide) retombe
     *                          sur le comportement sûr : pair TCP uniquement.
     */
    public ClientIpResolver(int trustedProxyCount) {
        this(trustedProxyCount, DEFAULT_IPV6_PREFIX_LENGTH);
    }

    /**
     * @param trustedProxyCount nombre de proxies de confiance devant le Gateway (voir constructeur
     *                          à un argument).
     * @param ipv6PrefixLength  longueur, en bits, du préfixe réseau IPv6 retenu comme clé de rate
     *                          limiting (décision N-2) — doit être compris entre {@code 1} et
     *                          {@code 128} inclus. Toute valeur hors bornes échoue immédiatement
     *                          ({@link IllegalArgumentException}) : aucun repli silencieux vers une
     *                          autre valeur, même discipline que {@code ServiceTokenCodec} (F-8).
     */
    public ClientIpResolver(int trustedProxyCount, int ipv6PrefixLength) {
        if (ipv6PrefixLength < MIN_IPV6_PREFIX_LENGTH || ipv6PrefixLength > MAX_IPV6_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "ipv6PrefixLength doit être compris entre " + MIN_IPV6_PREFIX_LENGTH
                            + " et " + MAX_IPV6_PREFIX_LENGTH + " inclus (valeur reçue : " + ipv6PrefixLength + ")");
        }
        this.delegate = trustedProxyCount > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyCount)
                : new RemoteAddressResolver() { };
        this.ipv6PrefixLength = ipv6PrefixLength;
    }

    /**
     * @return la clé IP cliente — adresse IPv4 littérale inchangée, préfixe IPv6 tronqué (décision
     *         N-2) sous la forme {@code "<préfixe-canonique>/<longueur>"}, ou {@code "unknown"} si
     *         elle ne peut pas être déterminée (jamais {@code null}).
     */
    public String resolve(ServerWebExchange exchange) {
        InetSocketAddress address = delegate.resolve(exchange);
        if (address == null) {
            return UNKNOWN_IP;
        }
        InetAddress inetAddress = address.getAddress();
        if (inetAddress == null) {
            String host = address.getHostString();
            return (host == null || host.isBlank()) ? UNKNOWN_IP : host;
        }
        if (inetAddress instanceof Inet6Address ipv6) {
            return truncateToPrefix(ipv6);
        }
        return inetAddress.getHostAddress();
    }

    /**
     * Tronque une adresse IPv6 au préfixe configuré en travaillant directement sur ses octets
     * (décision N-2) — jamais une manipulation de la représentation textuelle, qui serait sensible
     * à la forme compressée/développée. Reconstruit une {@link InetAddress} canonique :
     * {@link InetAddress#getHostAddress()} produit alors toujours la même chaîne pour un même
     * préfixe, quelle que soit la forme d'écriture d'origine de l'adresse source.
     */
    private String truncateToPrefix(Inet6Address ipv6) {
        byte[] bytes = ipv6.getAddress().clone();
        int fullBytes = ipv6PrefixLength / 8;
        int remainingBits = ipv6PrefixLength % 8;
        int firstZeroedByte = fullBytes + (remainingBits > 0 ? 1 : 0);
        for (int i = firstZeroedByte; i < bytes.length; i++) {
            bytes[i] = 0;
        }
        if (remainingBits > 0) {
            int mask = 0xFF << (8 - remainingBits);
            bytes[fullBytes] &= (byte) mask;
        }
        try {
            InetAddress truncated = InetAddress.getByAddress(bytes);
            return truncated.getHostAddress() + "/" + ipv6PrefixLength;
        } catch (UnknownHostException e) {
            // Ne peut pas se produire : bytes.length vaut toujours 16 (clone() d'une Inet6Address).
            throw new IllegalStateException("Adresse IPv6 tronquée invalide (ne devrait jamais arriver)", e);
        }
    }
}
