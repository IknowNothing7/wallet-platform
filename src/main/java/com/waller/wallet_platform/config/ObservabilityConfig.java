package com.waller.wallet_platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import io.micrometer.observation.ObservationPredicate;
import com.waller.wallet_platform.outbox.messaging.OutboxPublisher;

@Configuration
public class ObservabilityConfig {

    /**
     * No observation (span or tasks.scheduled.execution timer) for the outbox poll. It runs every second, so it would
     * be a near-empty trace per second; and while it is open, the Kafka send span would become its child instead of
     * joining the trace that recorded the event (OutboxPublisher restores that one from the outbox row).
     */
    @Bean
    public ObservationPredicate skipOutboxPollObservation() {
        return (name, context) -> !(context instanceof ScheduledTaskObservationContext task
                && OutboxPublisher.class.isAssignableFrom(task.getTargetClass()));
    }
}
