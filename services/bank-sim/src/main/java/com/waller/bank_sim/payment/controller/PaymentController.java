package com.waller.bank_sim.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import com.waller.bank_sim.payment.dto.PaymentDto;
import com.waller.bank_sim.payment.dto.PaymentRequest;
import com.waller.bank_sim.payment.service.PaymentService;

// The "bank" API that wallet-service calls; the result arrives later via webhook
@RestController
@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PaymentDto createPayment(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        return paymentService.createPayment(idempotencyKey, request);
    }

    @GetMapping("/{reference}")
    public PaymentDto getPayment(@PathVariable("reference") String reference) {
        return paymentService.getPayment(reference);
    }
}
