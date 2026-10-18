package com.waller.wallet_platform.deposit.service;

import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DepositAlerts {

    // UNREADABLE_WEBHOOK: a signed bank webhook whose body could not be parsed, stored in bank_webhook_dead_letter
    public enum Reason { UNKNOWN_DEPOSIT, RESULT_MISMATCH, STATUS_CONFLICT, UNREADABLE_WEBHOOK }

    private final MeterRegistry meterRegistry;

    public DepositAlerts(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Register every reason at 0 up front, otherwise Prometheus misses the first alert
        for (Reason reason : Reason.values()) {
            counter(reason);
        }
    }

    public void raise(Reason reason, String message, Object... args) {
        log.error("ALERT [" + reason + "] " + message, args);
        counter(reason).increment();
    }

    // Keep tag values few: no ids or references here, those go in the log line
    private Counter counter(Reason reason) {
        return Counter.builder("wallet.deposit.alerts")   // shows up as wallet_deposit_alerts_total
                .tag("reason", reason.name())
                .register(meterRegistry);
    }
}
