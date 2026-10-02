package com.tontiflow.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie le modèle de confiance de {@link ClientIpResolver} : par défaut
 * l'{@code X-Forwarded-For} est ignoré ; il n'est pris en compte, de façon
 * bornée, que lorsqu'un nombre explicite de proxies de confiance est déclaré.
 */
class ClientIpResolverTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("203.0.113.7", 44444);

    private static MockServerWebExchange exchange(InetSocketAddress remote, String xForwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/v1/tontines/1/members/claim");
        if (remote != null) {
            builder.remoteAddress(remote);
        }
        if (xForwardedFor != null) {
            builder.header("X-Forwarded-For", xForwardedFor);
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    void count0_ignoresXForwardedFor_usesTcpPeer() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        String ip = resolver.resolve(exchange(PEER, "1.1.1.1"));

        assertThat(ip).isEqualTo("203.0.113.7");
    }

    @Test
    void count0_noXff_usesTcpPeer() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        assertThat(resolver.resolve(exchange(PEER, null))).isEqualTo("203.0.113.7");
    }

    @Test
    void negativeCount_isTreatedAsSafeDefault() {
        ClientIpResolver resolver = new ClientIpResolver(-3);

        String ip = resolver.resolve(exchange(PEER, "1.1.1.1, 2.2.2.2"));

        assertThat(ip).isEqualTo("203.0.113.7");
    }

    @Test
    void count1_withTrustedProxy_returnsLastTrustedEntry_notClientSuppliedPrefix() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        String ip = resolver.resolve(exchange(PEER, "1.1.1.1, 2.2.2.2, 3.3.3.3"));

        // 1 proxy de confiance -> on garde la derniere entree, jamais le prefixe
        // que le client peut forger librement.
        assertThat(ip).isEqualTo("3.3.3.3");
    }

    @Test
    void count2_returnsSecondEntryFromEnd() {
        ClientIpResolver resolver = new ClientIpResolver(2);

        String ip = resolver.resolve(exchange(PEER, "1.1.1.1, 2.2.2.2, 3.3.3.3"));

        assertThat(ip).isEqualTo("2.2.2.2");
    }

    @Test
    void count1_noXff_fallsBackToTcpPeer() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        assertThat(resolver.resolve(exchange(PEER, null))).isEqualTo("203.0.113.7");
    }

    @Test
    void noTcpPeerAndNoXff_returnsUnknown() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        assertThat(resolver.resolve(exchange(null, null))).isEqualTo("unknown");
    }

    // ------------------------------------------------------------------
    // N-2 : normalisation IPv6 par préfixe (ClientIpResolver.resolve)
    // ------------------------------------------------------------------

    private static MockServerWebExchange exchangeWithPeer(String ip) {
        return exchange(new InetSocketAddress(ip, 44444), null);
    }

    @Test
    void ipv4_isNeverTruncated_literalAddressUnchanged() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        assertThat(resolver.resolve(exchangeWithPeer("192.168.1.10"))).isEqualTo("192.168.1.10");
    }

    @Test
    void twoDifferentIpv4Addresses_remainDistinct() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        String a = resolver.resolve(exchangeWithPeer("192.168.1.10"));
        String b = resolver.resolve(exchangeWithPeer("192.168.1.11"));

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo("192.168.1.10");
        assertThat(b).isEqualTo("192.168.1.11");
    }

    @Test
    void twoIpv6AddressesOfSamePrefix_produceTheSameKey() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        String a = resolver.resolve(exchangeWithPeer("2001:db8:1234:5678::1"));
        String b = resolver.resolve(exchangeWithPeer("2001:db8:1234:5678::2"));
        String c = resolver.resolve(exchangeWithPeer("2001:db8:1234:5678::ffff"));

        assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo("2001:db8:1234:5678:0:0:0:0/64");
    }

    @Test
    void twoIpv6AddressesOfDifferentPrefixes_produceDifferentKeys() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        String a = resolver.resolve(exchangeWithPeer("2001:db8:1234:5678::1"));
        String b = resolver.resolve(exchangeWithPeer("2001:db8:1234:5679::1"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void ipv6CompressedAndExpandedForms_produceTheSameKey() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        String compressed = resolver.resolve(exchangeWithPeer("2001:db8:1234:5678::1"));
        String expanded = resolver.resolve(exchangeWithPeer("2001:0db8:1234:5678:0000:0000:0000:0001"));

        assertThat(compressed).isEqualTo(expanded);
    }

    @Test
    void ipv4MappedIpv6Literal_isExposedAsIpv4_andNeverTruncated() {
        // Vérifié empiriquement (JDK 21, cette base de code) : le JDK normalise toujours une
        // adresse IPv4-mappée IPv6 en Inet4Address, y compris depuis une vraie connexion socket -
        // ce test confirme que ClientIpResolver en hérite naturellement, sans code spécial.
        ClientIpResolver resolver = new ClientIpResolver(0);

        String resolved = resolver.resolve(exchangeWithPeer("::ffff:192.168.1.10"));

        assertThat(resolved).isEqualTo("192.168.1.10");
    }

    @Test
    void unknownIp_behaviorUnchanged() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        assertThat(resolver.resolve(exchange(null, null))).isEqualTo("unknown");
    }

    @Test
    void validIpv6_isNeverResolvedAsUnknown() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        assertThat(resolver.resolve(exchangeWithPeer("2001:db8:1234:5678::1"))).isNotEqualTo("unknown");
    }

    @Test
    void configurablePrefixLength_isHonored() {
        ClientIpResolver resolver48 = new ClientIpResolver(0, 48);

        String a = resolver48.resolve(exchangeWithPeer("2001:db8:1234:5678::1"));
        String b = resolver48.resolve(exchangeWithPeer("2001:db8:1234:9999::1"));

        // Même /48 (2001:db8:1234::/48) malgré des 4e groupes différents (5678 vs 9999).
        assertThat(a).isEqualTo(b).isEqualTo("2001:db8:1234:0:0:0:0:0/48");
    }

    @Test
    void prefixLength128_keepsEachAddressDistinct_equivalentToPreCorrectionBehavior() {
        ClientIpResolver resolver128 = new ClientIpResolver(0, 128);

        String a = resolver128.resolve(exchangeWithPeer("2001:db8:1234:5678::1"));
        String b = resolver128.resolve(exchangeWithPeer("2001:db8:1234:5678::2"));

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo("2001:db8:1234:5678:0:0:0:1/128");
    }

    @Test
    void prefixLengthBelowMinimum_isRejectedAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClientIpResolver(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefixLengthAboveMaximum_isRejectedAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClientIpResolver(0, 129))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
