package com.waller.bank_sim.payment.service;

import java.util.List;
import com.waller.bank_sim.payment.dto.PaymentDto;
import com.waller.bank_sim.payment.dto.PaymentRequest;

public interface PaymentService {

    /** Idempotent by key: a retry with the same key returns the original payment and sends no second webhook. */
    PaymentDto createPayment(String idempotencyKey, PaymentRequest request);

    PaymentDto getPayment(String reference);

    List<PaymentDto> getPayments();
}
