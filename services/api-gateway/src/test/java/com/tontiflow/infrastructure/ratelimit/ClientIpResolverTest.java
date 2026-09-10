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
}
