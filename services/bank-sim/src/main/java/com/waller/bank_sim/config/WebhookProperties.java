package com.waller.bank_sim.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank-sim.webhook")
public record WebhookProperties(
        String url,
        String secret,
        Duration delay,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration requestTimeout
) {
}
