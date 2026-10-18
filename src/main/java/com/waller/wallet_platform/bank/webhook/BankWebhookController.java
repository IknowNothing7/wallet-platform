package com.waller.wallet_platform.bank.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.waller.wallet_platform.bank.dto.BankPaymentResult;
import com.waller.wallet_platform.deposit.service.DepositAlerts;
import com.waller.wallet_platform.deposit.service.DepositProcessingService;

// Called by bank-sim, not by users: no JWT, authenticated by the HMAC signature instead (permitted in SecurityConfig)
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhooks")
@Slf4j
public class BankWebhookController {

    private final BankWebhookVerifier verifier;
    private final JsonMapper jsonMapper;
    private final DepositProcessingService depositProcessingService;
    private final BankWebhookDeadLetterRepository deadLetterRepository;
    private final DepositAlerts depositAlerts;

    // Any non-2xx makes bank-sim retry with backoff, so only answer an error when a retry could succeed
    @PostMapping("/bank")
    public void receivePaymentResult(
            @RequestHeader(name = BankWebhookVerifier.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(name = BankWebhookVerifier.SIGNATURE_HEADER, required = false) String signature,
            // Raw string, not a DTO: the signature is over the exact bytes that were sent
            @RequestBody String body) {
        if (!verifier.isValid(timestamp, signature, body)) {
            log.warn("Rejected bank webhook with an invalid signature or timestamp");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid signature");
        }

        BankPaymentResult result;
        try {
            result = jsonMapper.readValue(body, BankPaymentResult.class);
        } catch (JacksonException e) {
            // Signed but unreadable: a contract mismatch. A retry sends the same body, so it would fail forever:
            // keep it for manual review and acknowledge. If saving fails the 500 makes bank-sim retry, which is fine.
            BankWebhookDeadLetter deadLetter = deadLetterRepository.save(
                    new BankWebhookDeadLetter(String.valueOf(e.getOriginalMessage()), body));
            depositAlerts.raise(DepositAlerts.Reason.UNREADABLE_WEBHOOK,
                    "Unreadable bank webhook stored as dead letter {}: {}", deadLetter.getId(), e.getOriginalMessage());
            return;
        }

        // Rate and size limits are applied before this, in BankWebhookLimitFilter
        depositProcessingService.applyResult(result);
    }
}
