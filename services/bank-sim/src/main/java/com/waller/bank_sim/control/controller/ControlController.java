package com.waller.bank_sim.control.controller;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.waller.bank_sim.control.dto.Scenario;
import com.waller.bank_sim.control.service.ScenarioService;
import com.waller.bank_sim.payment.dto.PaymentDto;
import com.waller.bank_sim.payment.repository.PaymentStore;
import com.waller.bank_sim.payment.service.PaymentService;
import com.waller.bank_sim.webhook.service.WebhookSender;

// For tests and demos only; must not be reachable through the public ingress
@RestController
@RequiredArgsConstructor
@RequestMapping("/control")
public class ControlController {

    private final ScenarioService scenarioService;
    private final PaymentService paymentService;
    private final PaymentStore paymentStore;
    private final WebhookSender webhookSender;

    @GetMapping("/scenario")
    public Scenario getScenario() {
        return scenarioService.get();
    }

    @PutMapping("/scenario")
    public Scenario setScenario(@Valid @RequestBody Scenario scenario) {
        return scenarioService.set(scenario);
    }

    @GetMapping("/payments")
    public List<PaymentDto> getPayments() {
        return paymentService.getPayments();
    }

    @PostMapping("/payments/{reference}/webhook")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendWebhook(@PathVariable("reference") String reference) {
        webhookSender.resend(reference);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        scenarioService.reset();
        paymentStore.clear();
    }
}
