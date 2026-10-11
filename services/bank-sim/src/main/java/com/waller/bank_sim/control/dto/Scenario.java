package com.waller.bank_sim.control.dto;

import java.time.Duration;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** How the simulated bank behaves; switched at runtime through the control API to exercise wallet-service. */
public record Scenario(
        @NotNull Outcome outcome,
        // Used only with outcome RANDOM
        @DecimalMin("0.0") @DecimalMax("1.0") double declineRate,
        // Added to every POST /payments, to trip wallet-service's timeout
        @NotNull Duration latency,
        // Share of requests answered 503 before the payment is stored
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate,
        // Share of requests answered 503 after the payment is stored (the retry must not double the top-up)
        @DecimalMin("0.0") @DecimalMax("1.0") double lostResponseRate,
        @NotNull WebhookMode webhookMode
) {

    public enum Outcome { APPROVE, DECLINE, RANDOM }

    public enum WebhookMode {
        NORMAL,
        // Every result is delivered twice, to check deduplication by reference
        DUPLICATE,
        // Results are never delivered; wallet-service has to reconcile through GET /payments/{reference}
        DROP
    }

    public static Scenario happyPath() {
        return new Scenario(Outcome.APPROVE, 0.0, Duration.ZERO, 0.0, 0.0, WebhookMode.NORMAL);
    }
}
