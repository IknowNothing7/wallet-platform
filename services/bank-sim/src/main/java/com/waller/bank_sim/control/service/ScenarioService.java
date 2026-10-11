package com.waller.bank_sim.control.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.waller.bank_sim.control.dto.PaymentOutcome;
import com.waller.bank_sim.control.dto.Scenario;

@Service
public class ScenarioService {

    private static final String DECLINED = "Declined by issuer";
    private static final String UNAVAILABLE = "Simulated bank outage";

    private final AtomicReference<Scenario> current = new AtomicReference<>(Scenario.happyPath());

    public Scenario get() {
        return current.get();
    }

    public Scenario set(Scenario scenario) {
        current.set(scenario);
        return scenario;
    }

    public Scenario reset() {
        return set(Scenario.happyPath());
    }

    public void applyBeforeProcessing() {
        Scenario scenario = current.get();
        sleep(scenario);
        if (roll(scenario.failureRate())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
    }

    public void applyAfterProcessing() {
        if (roll(current.get().lostResponseRate())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
    }

    public PaymentOutcome decideOutcome() {
        Scenario scenario = current.get();
        boolean declined = switch (scenario.outcome()) {
            case APPROVE -> false;
            case DECLINE -> true;
            case RANDOM -> roll(scenario.declineRate());
        };
        return declined ? PaymentOutcome.declined(DECLINED) : PaymentOutcome.success();
    }

    private static boolean roll(double rate) {
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }

    private static void sleep(Scenario scenario) {
        if (scenario.latency().isZero() || scenario.latency().isNegative()) {
            return;
        }
        try {
            Thread.sleep(scenario.latency());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
