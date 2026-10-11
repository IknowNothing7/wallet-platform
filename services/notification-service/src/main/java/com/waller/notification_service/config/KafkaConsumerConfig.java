package com.waller.notification_service.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConsumerConfig {

    // Picked up by Boot's listener container factory: retries with backoff, then publishes to <topic>.DLT
    // TODO: KafkaTemplate needs producer config (string serializers) for the DLT publisher
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${notification.consumer.initial-backoff}") Duration initialBackoff,
            @Value("${notification.consumer.max-backoff}") Duration maxBackoff,
            @Value("${notification.consumer.max-attempts}") int maxAttempts) {
        ExponentialBackOff backOff = new ExponentialBackOff(initialBackoff.toMillis(), 2.0);
        backOff.setMaxInterval(maxBackoff.toMillis());
        backOff.setMaxAttempts(maxAttempts);
        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), backOff);
        // Malformed records (missing header/field, bad JSON) go straight to the DLT; retrying cannot fix them
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
        return errorHandler;
    }
}
