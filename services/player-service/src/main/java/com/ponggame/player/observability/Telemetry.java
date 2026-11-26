package com.ponggame.player.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

public final class Telemetry {
    private Telemetry() {}

    public static Operation start(String name) {
        return new Operation(GlobalOpenTelemetry.getTracer("ponggame/player")
                .spanBuilder(name).startSpan());
    }

    public static void annotate(String matchId, String messageId, String correlationId) {
        Span span = Span.current();
        if (matchId != null) span.setAttribute("pong.match.id", matchId);
        if (messageId != null) span.setAttribute("messaging.message.id", messageId);
        if (correlationId != null) span.setAttribute("pong.correlation.id", correlationId);
    }

    public static void count(String name, String outcome) {
        GlobalOpenTelemetry.getMeter("ponggame/player").counterBuilder(name).build()
                .add(1, Attributes.of(AttributeKey.stringKey("outcome"), outcome));
    }

    public static final class Operation implements AutoCloseable {
        public final Span span;
        private final Scope scope;
        Operation(Span span) { this.span = span; this.scope = span.makeCurrent(); }
        public void error(Throwable error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
        }
        @Override public void close() { scope.close(); span.end(); }
    }
}
