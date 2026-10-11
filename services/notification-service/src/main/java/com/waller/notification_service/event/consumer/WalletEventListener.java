package com.waller.notification_service.event.consumer;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;
import com.waller.notification_service.event.service.WalletEvent;
import com.waller.notification_service.event.service.WalletEventHandler;

@Component
@RequiredArgsConstructor
public class WalletEventListener {

    private final WalletEventHandler walletEventHandler;
    private final JsonMapper jsonMapper;

    // Header names match wallet-service's OutboxPublisher
    @KafkaListener(topicPattern = "${notification.consumer.topic-pattern}")
    public void onEvent(ConsumerRecord<String, String> record) {
        walletEventHandler.handle(new WalletEvent(
                Long.parseLong(header(record, "event-id")),
                header(record, "event-type"),
                header(record, "aggregate-type"),
                Long.parseLong(header(record, "aggregate-id")),
                jsonMapper.readTree(record.value())));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            throw new IllegalArgumentException("Record on " + record.topic() + " has no '" + name + "' header");
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
