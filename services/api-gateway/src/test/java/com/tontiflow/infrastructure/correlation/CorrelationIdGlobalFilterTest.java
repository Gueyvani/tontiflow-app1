package com.tontiflow.infrastructure.correlation;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void filter_withoutHeader_generatesUuid() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tontines"));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        filter.filter(exchange, capturingChain(downstream)).block();

        String correlationId = downstream.get().getRequest().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        assertThat(correlationId).isNotBlank();
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void filter_withValidUuidHeader_keepsValue() {
        String existing = UUID.randomUUID().toString();
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/tontines")
                        .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, existing));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        filter.filter(exchange, capturingChain(downstream)).block();

        String correlationId = downstream.get().getRequest().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        assertThat(correlationId).isEqualTo(existing);
    }

    @Test
    void filter_withBlankHeader_generatesUuid() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/tontines")
                        .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, ""));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        filter.filter(exchange, capturingChain(downstream)).block();

        String correlationId = downstream.get().getRequest().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        assertThat(correlationId).isNotBlank();
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void filter_withInvalidUuidHeader_regeneratesValue() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/tontines")
                        .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, "not-a-uuid"));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        filter.filter(exchange, capturingChain(downstream)).block();

        String correlationId = downstream.get().getRequest().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        assertThat(correlationId).isNotEqualTo("not-a-uuid");
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void filter_addsCorrelationIdToResponse() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tontines"));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        filter.filter(exchange, capturingChain(downstream)).block();

        String responseCorrelationId = exchange.getResponse().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        assertThat(responseCorrelationId).isNotBlank();
        assertThat(UUID.fromString(responseCorrelationId)).isNotNull();
    }

    @Test
    void filter_propagatesCorrelationIdToDownstreamRequest() {
        String existing = UUID.randomUUID().toString();
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/tontines")
                        .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, existing));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        filter.filter(exchange, capturingChain(downstream)).block();

        ServerHttpRequest downstreamRequest = downstream.get().getRequest();
        assertThat(downstreamRequest.getHeaders().getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER))
                .isEqualTo(existing);
    }

    private static GatewayFilterChain capturingChain(AtomicReference<ServerWebExchange> capture) {
        return ex -> {
            capture.set(ex);
            return Mono.empty();
        };
    }
}
