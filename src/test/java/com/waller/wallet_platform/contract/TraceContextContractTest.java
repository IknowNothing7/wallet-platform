package com.waller.wallet_platform.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import com.waller.wallet_platform.outbox.entity.OutboxEvent;
import com.waller.wallet_platform.outbox.messaging.OutboxPublisher;
import com.waller.wallet_platform.outbox.messaging.TraceParent;
import com.waller.wallet_platform.outbox.repository.OutboxRepository;
import com.waller.wallet_platform.support.TestData;

/**
 * SKETCH. The trace header contract of wallet.* events (contracts/README.md, "Headers"): every record carries a W3C
 * {@code traceparent} that continues the trace of the request which recorded the event. notification-service relies on
 * it to join that trace.
 *
 * <p>Runs the real OutboxPublisher, KafkaTemplate observation and OpenTelemetry propagator, wired by hand as Boot
 * would; only the broker is a MockProducer and the repository a mock.
 */
class TraceContextContractTest {

    // W3C Trace Context, version 00: version-traceid-parentid-flags, lowercase hex. Flags are a bit field: bit 0 is
    // "sampled"; the OTel SDK also sets bit 1 ("random trace id", Level 2) on new traces, so consumers must not
    // insist on 00/01.
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    private static final String RECORDED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String RECORDED_SPAN_ID = "00f067aa0ba902b7";

    private Tracer tracer;
    private MockProducer<String, String> producer;
    private OutboxRepository repository;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        io.opentelemetry.api.trace.Tracer otelTracer = SdkTracerProvider.builder().build().get("contract-test");
        tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> { });
        OtelPropagator propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otelTracer);

        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));

        producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(new MockProducerFactory<>(() -> producer));
        kafkaTemplate.setObservationEnabled(true);   // spring.kafka.template.observation-enabled
        kafkaTemplate.setObservationRegistry(registry);

        repository = mock(OutboxRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(
                invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        publisher = new OutboxPublisher(repository, kafkaTemplate, transactionTemplate, tracer,
                "wallet.", 100, 10, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    @Test
    void eventContinuesTheTraceThatRecordedIt() {
        OutboxEvent event = event(1L);
        event.setTraceParent("00-" + RECORDED_TRACE_ID + "-" + RECORDED_SPAN_ID + "-01");

        Matcher header = publishAndReadTraceparent(event);

        assertThat(header.group(1)).as("trace id").isEqualTo(RECORDED_TRACE_ID);
        // A child span (outbox publish -> send), never the recording span itself
        assertThat(header.group(2)).as("parent span id").isNotEqualTo(RECORDED_SPAN_ID);
        assertThat(sampled(header)).as("sampled flag").isTrue();
    }

    @Test
    void eventWithoutRecordedTraceStillCarriesAValidTraceparent() {
        // Rows from before V15, or recorded outside any trace
        Matcher header = publishAndReadTraceparent(event(2L));

        assertThat(header.group(1)).isNotEqualTo("0".repeat(32));
        assertThat(sampled(header)).as("sampled flag").isTrue();
    }

    @Test
    void recordedTraceparentIsTheCurrentSpan() {
        Span span = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(TraceParent.current(tracer)).isEqualTo(
                    "00-" + span.context().traceId() + "-" + span.context().spanId() + "-01");
        } finally {
            span.end();
        }
    }

    // TODO consumer side (notification-service): a record with this traceparent is handled in a span of the same
    //  trace. HTTP side: BankClient -> bank-sim and the webhook back send traceparent.

    private Matcher publishAndReadTraceparent(OutboxEvent event) {
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(event));

        publisher.publishPending();

        List<ProducerRecord<String, String>> sent = producer.history();
        assertThat(sent).hasSize(1);
        Header header = sent.getFirst().headers().lastHeader("traceparent");
        assertThat(header).as("traceparent header").isNotNull();
        String value = new String(header.value(), StandardCharsets.UTF_8);
        Matcher matcher = TRACEPARENT.matcher(value);
        assertThat(matcher.matches()).as("%s is a W3C traceparent", value).isTrue();
        return matcher;
    }

    private static boolean sampled(Matcher header) {
        return (Integer.parseInt(header.group(3), 16) & 0x01) != 0;
    }

    private static OutboxEvent event(long id) {
        OutboxEvent event = TestData.outboxEvent("deposit", 42L, "DepositCreated");
        event.setId(id);
        return event;
    }
}
