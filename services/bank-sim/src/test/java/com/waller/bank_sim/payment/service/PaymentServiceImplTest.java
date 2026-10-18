package com.waller.bank_sim.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.waller.bank_sim.control.dto.PaymentOutcome;
import com.waller.bank_sim.control.service.ScenarioService;
import com.waller.bank_sim.payment.dto.PaymentDto;
import com.waller.bank_sim.payment.dto.PaymentRequest;
import com.waller.bank_sim.payment.model.PaymentStatus;
import com.waller.bank_sim.payment.repository.PaymentStore;
import com.waller.bank_sim.webhook.service.WebhookSender;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    private static final PaymentRequest REQUEST = new PaymentRequest(1000L, "USD", "dep-1");

    @Mock
    private ScenarioService scenarioService;

    @Mock
    private WebhookSender webhookSender;

    // Real store: the idempotency guarantee lives in it
    private final PaymentStore paymentStore = new PaymentStore();

    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImpl(paymentStore, scenarioService, webhookSender);
    }

    @Test
    void createPayment_storesPendingPaymentAndSchedulesItsResult() {
        when(scenarioService.decideOutcome()).thenReturn(PaymentOutcome.success());

        PaymentDto result = paymentService.createPayment("key-1", REQUEST);

        assertThat(result.reference()).startsWith("bsim_");
        assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.amount()).isEqualTo(1000L);
        assertThat(result.walletDepositId()).isEqualTo("dep-1");
        assertThat(paymentService.getPayment(result.reference())).isEqualTo(result);
        verify(webhookSender).scheduleResult(any(), any());
    }

    @Test
    void sameIdempotencyKey_returnsSamePaymentAndSchedulesOnce() {
        when(scenarioService.decideOutcome()).thenReturn(PaymentOutcome.success());

        PaymentDto first = paymentService.createPayment("key-1", REQUEST);
        PaymentDto second = paymentService.createPayment("key-1", REQUEST);

        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(paymentService.getPayments()).hasSize(1);
        verify(webhookSender, times(1)).scheduleResult(any(), any());
    }

    @Test
    void lostResponse_retryWithSameKeyDoesNotCreateSecondPayment() {
        when(scenarioService.decideOutcome()).thenReturn(PaymentOutcome.success());
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .doNothing()
                .when(scenarioService).applyAfterProcessing();

        assertThatThrownBy(() -> paymentService.createPayment("key-1", REQUEST))
                .isInstanceOf(ResponseStatusException.class);
        PaymentDto retried = paymentService.createPayment("key-1", REQUEST);

        assertThat(paymentService.getPayments()).extracting(PaymentDto::reference).containsExactly(retried.reference());
        verify(webhookSender, times(1)).scheduleResult(any(), any());
    }

    @Test
    void failureBeforeProcessing_storesNothing() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .when(scenarioService).applyBeforeProcessing();

        assertThatThrownBy(() -> paymentService.createPayment("key-1", REQUEST))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(paymentService.getPayments()).isEmpty();
        verifyNoInteractions(webhookSender);
    }

    @Test
    void getPayment_unknownReference_returns404() {
        assertThatThrownBy(() -> paymentService.getPayment("bsim_missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
