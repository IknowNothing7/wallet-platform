package com.waller.notification_service.event.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.waller.notification_service.event.repository.ProcessedEventRepository;
import com.waller.notification_service.event.service.UnknownAccountException;
import com.waller.notification_service.event.service.WalletEventHandler;

@Testcontainers
@SpringBootTest(properties = {
        "notification.consumer.initial-backoff=PT0.1S",
        "notification.consumer.max-backoff=PT0.2S",
        "notification.consumer.max-attempts=" + WalletEventDlqTest.MAX_ATTEMPTS,
})
class WalletEventDlqTest {

    static final int MAX_ATTEMPTS = 2;
    private static final String TOPIC = "wallet.transfer";
    private static final String DLT = TOPIC + ".DLT";
    private static final AtomicLong EVENT_IDS = new AtomicLong(1000);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgresql = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

    // Created before the listener starts, so the pattern subscription sees them on its first metadata fetch
    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic walletTransfer() {
            return new NewTopic(TOPIC, 1, (short) 1);
        }

        @Bean
        NewTopic walletTransferDlt() {
            return new NewTopic(DLT, 1, (short) 1);
        }
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @MockitoSpyBean
    private WalletEventHandler walletEventHandler;

    @Test
    void recordWithoutHeaders_goesStraightToDlt() throws Exception {
        String key = UUID.randomUUID().toString();
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, key, "{\"amount\":100}")).get();

        ConsumerRecord<String, String> dead = awaitDeadLetter(key);

        assertThat(dead.value()).isEqualTo("{\"amount\":100}");
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(IllegalArgumentException.class.getName());
        verify(walletEventHandler, never()).handle(any());
    }

    @Test
    void malformedJson_goesStraightToDltWithoutReachingHandler() throws Exception {
        long eventId = EVENT_IDS.incrementAndGet();
        String key = UUID.randomUUID().toString();
        kafkaTemplate.send(walletRecord(key, eventId, "TransferCompleted", "{not json")).get();

        ConsumerRecord<String, String> dead = awaitDeadLetter(key);

        // Original outbox headers travel with the dead letter, so it can be replayed as is
        assertThat(header(dead, "event-id")).isEqualTo(String.valueOf(eventId));
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotBlank();
        verify(walletEventHandler, never()).handle(any());
    }

    @Test
    void retryableFailure_isRetriedThenDeadLettered() throws Exception {
        long eventId = EVENT_IDS.incrementAndGet();
        String key = UUID.randomUUID().toString();
        // No AccountCreated was consumed for these accounts, so every attempt throws UnknownAccountException
        String payload = """
                {"fromAccountId": 900001, "toAccountId": 900002, "amount": 100, "currency": "USD"}""";
        kafkaTemplate.send(walletRecord(key, eventId, "TransferCompleted", payload)).get();

        ConsumerRecord<String, String> dead = awaitDeadLetter(key);

        assertThat(dead.value()).isEqualTo(payload);
        assertThat(header(dead, "event-id")).isEqualTo(String.valueOf(eventId));
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(UnknownAccountException.class.getName());
        // First delivery plus one per backoff attempt; the rolled-back handler left no inbox row behind
        verify(walletEventHandler, times(MAX_ATTEMPTS + 1)).handle(any());
        assertThat(processedEventRepository.existsById(eventId)).isFalse();

        // The DLT is not itself consumed (it would match a naive wallet\..* pattern)
        Thread.sleep(1000);
        verify(walletEventHandler, times(MAX_ATTEMPTS + 1)).handle(any());
    }

    private static ProducerRecord<Object, Object> walletRecord(String key, long eventId, String eventType,
                                                               String payload) {
        ProducerRecord<Object, Object> record = new ProducerRecord<>(TOPIC, key, payload);
        addHeader(record, "event-id", String.valueOf(eventId));
        addHeader(record, "event-type", eventType);
        addHeader(record, "aggregate-type", "transfer");
        addHeader(record, "aggregate-id", "42");
        return record;
    }

    private static void addHeader(ProducerRecord<Object, Object> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header %s", name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    // Tests share the DLT, so each one picks out its own record by key
    private static ConsumerRecord<String, String> awaitDeadLetter(String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(DLT));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " reached " + DLT);
    }
}
