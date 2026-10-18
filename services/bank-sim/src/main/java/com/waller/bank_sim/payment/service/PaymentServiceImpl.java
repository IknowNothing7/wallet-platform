package com.waller.bank_sim.payment.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.waller.bank_sim.control.service.ScenarioService;
import com.waller.bank_sim.payment.dto.PaymentDto;
import com.waller.bank_sim.payment.dto.PaymentRequest;
import com.waller.bank_sim.payment.model.Payment;
import com.waller.bank_sim.payment.model.PaymentStatus;
import com.waller.bank_sim.payment.repository.PaymentStore;
import com.waller.bank_sim.webhook.service.WebhookSender;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final String PAYMENT_NOT_FOUND = "Payment not found";
    private static final String CANCELLED = "Cancelled by merchant";
    private static final String KEY_REUSED = "Idempotency key was already used for a different payment";

    private final PaymentStore paymentStore;
    private final ScenarioService scenarioService;
    private final WebhookSender webhookSender;

    @Override
    public PaymentDto createPayment(String idempotencyKey, PaymentRequest request) {
        // Fault injection before anything is stored: wallet-service sees a slow or failed call and retries
        scenarioService.applyBeforeProcessing();

        Payment payment = paymentStore.getOrCreate(idempotencyKey, key -> {
            Payment created = new Payment(
                    "bsim_" + UUID.randomUUID(), key, request.amount(), request.currency(), request.walletDepositId());
            webhookSender.scheduleResult(created, scenarioService.decideOutcome());
            log.info("Accepted payment {} for deposit {}", created.getReference(), created.getWalletDepositId());
            return created;
        });

        // A retry must be the same request; anything else is a client bug, not a second payment
        if (!matches(payment, request)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, KEY_REUSED);
        }

        // Fault injection after storing: the payment exists but the response is "lost", so a retry
        // must hit the idempotency key instead of creating a second top-up
        scenarioService.applyAfterProcessing();
        return PaymentDto.of(payment);
    }

    @Override
    public PaymentDto getPayment(String reference) {
        return paymentStore.findByReference(reference)
                .map(PaymentDto::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND));
    }

    @Override
    public PaymentDto cancelPayment(String reference) {
        Payment payment = paymentStore.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND));
        if (payment.settle(PaymentStatus.CANCELLED, CANCELLED)) {
            log.info("Cancelled payment {} for deposit {}", payment.getReference(), payment.getWalletDepositId());
        }
        return PaymentDto.of(payment);
    }

    @Override
    public List<PaymentDto> getPayments() {
        return paymentStore.findAll().stream()
                .sorted(Comparator.comparing(Payment::getCreatedAt).reversed())
                .map(PaymentDto::of)
                .toList();
    }

    private static boolean matches(Payment payment, PaymentRequest request) {
        return payment.getAmount() == request.amount()
                && payment.getCurrency().equals(request.currency())
                && payment.getWalletDepositId().equals(request.walletDepositId());
    }
}
