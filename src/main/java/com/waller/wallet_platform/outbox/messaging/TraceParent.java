package com.waller.wallet_platform.outbox.messaging;

import java.util.Optional;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * Stores a span as a W3C traceparent ({@code 00-<trace id>-<span id>-<flags>}) on an outbox row, and turns it back into
 * a parent for the publish span. The outbox is polled on another thread, possibly on another instance, so the trace
 * can't be carried in memory.
 */
public final class TraceParent {

    private static final String VERSION = "00";

    private TraceParent() {
    }

    /** The traceparent of the current span, or null when there is none (e.g. tracing disabled). */
    public static String current(Tracer tracer) {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) {
            return null;
        }
        TraceContext context = span.context();
        boolean sampled = Boolean.TRUE.equals(context.sampled());
        return VERSION + "-" + context.traceId() + "-" + context.spanId() + "-" + (sampled ? "01" : "00");
    }

    /** Empty for null or malformed values: the event is still published, just without the original trace. */
    public static Optional<TraceContext> parse(Tracer tracer, String traceParent) {
        if (traceParent == null) {
            return Optional.empty();
        }
        String[] parts = traceParent.split("-");
        if (parts.length != 4 || parts[1].length() != 32 || parts[2].length() != 16) {
            return Optional.empty();
        }
        return Optional.of(tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled("01".equals(parts[3]))
                .build());
    }
}
