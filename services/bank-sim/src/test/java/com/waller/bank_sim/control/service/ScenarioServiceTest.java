package com.waller.bank_sim.control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.bank_sim.control.dto.PaymentOutcome;
import com.waller.bank_sim.control.dto.Scenario;
import com.waller.bank_sim.control.dto.Scenario.Outcome;
import com.waller.bank_sim.control.dto.Scenario.WebhookMode;

// Rates are only tested at 0 and 1, where the result doesn't depend on the random roll
class ScenarioServiceTest {

    private final ScenarioService scenarioService = new ScenarioService();

    @Test
    void happyPath_neverFailsAndApproves() {
        assertThatCode(scenarioService::applyBeforeProcessing).doesNotThrowAnyException();
        assertThatCode(scenarioService::applyAfterProcessing).doesNotThrowAnyException();
        assertThat(scenarioService.decideOutcome()).isEqualTo(PaymentOutcome.success());
    }

    @Test
    void declineOutcome_declinesWithReason() {
        scenarioService.set(scenario(Outcome.DECLINE, 0.0, 0.0, 0.0));

        PaymentOutcome outcome = scenarioService.decideOutcome();

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.failureReason()).isEqualTo("Declined by issuer");
    }

    @Test
    void randomOutcome_followsDeclineRate() {
        scenarioService.set(scenario(Outcome.RANDOM, 1.0, 0.0, 0.0));
        assertThat(scenarioService.decideOutcome().succeeded()).isFalse();

        scenarioService.set(scenario(Outcome.RANDOM, 0.0, 0.0, 0.0));
        assertThat(scenarioService.decideOutcome().succeeded()).isTrue();
    }

    @Test
    void fullFailureRate_answers503BeforeProcessing() {
        scenarioService.set(scenario(Outcome.APPROVE, 0.0, 1.0, 0.0));

        assertThatThrownBy(scenarioService::applyBeforeProcessing)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatCode(scenarioService::applyAfterProcessing).doesNotThrowAnyException();
    }

    @Test
    void fullLostResponseRate_answers503AfterProcessing() {
        scenarioService.set(scenario(Outcome.APPROVE, 0.0, 0.0, 1.0));

        assertThatCode(scenarioService::applyBeforeProcessing).doesNotThrowAnyException();
        assertThatThrownBy(scenarioService::applyAfterProcessing)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void reset_restoresHappyPath() {
        scenarioService.set(scenario(Outcome.DECLINE, 0.0, 1.0, 1.0));

        assertThat(scenarioService.reset()).isEqualTo(Scenario.happyPath());
        assertThat(scenarioService.get()).isEqualTo(Scenario.happyPath());
    }

    private static Scenario scenario(Outcome outcome, double declineRate, double failureRate, double lostResponseRate) {
        return new Scenario(outcome, declineRate, Duration.ZERO, failureRate, lostResponseRate, WebhookMode.NORMAL);
    }
}
