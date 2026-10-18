package com.waller.wallet_platform.bank.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class BankWebhookVerifierTest {

    // Same vector as bank-sim's WebhookSignerTest: if either side changes the signed
    // format or encoding, both tests must change together
    private static final String SECRET = "test-secret";
    private static final String TIMESTAMP = "1700000000";
    private static final String BODY = "{\"reference\":\"bsim_1\",\"status\":\"SUCCEEDED\"}";
    private static final String SIGNATURE =
            "sha256=3099092c33360dfa720dd1120a0ff4f10451b3a951f36177bbbd805fede7fd02";

    // Wide enough that the fixed 2023 timestamp above is still accepted
    private static final Duration LONG_TOLERANCE = Duration.ofDays(365L * 100);
    private final BankWebhookVerifier verifier = new BankWebhookVerifier(SECRET, "", LONG_TOLERANCE);

    @Test
    void acceptsSignatureProducedByBankSim() {
        assertThat(verifier.isValid(TIMESTAMP, SIGNATURE, BODY)).isTrue();
    }

    @Test
    void headerNamesMatchBankSim() {
        assertThat(BankWebhookVerifier.SIGNATURE_HEADER).isEqualTo("X-Bank-Signature");
        assertThat(BankWebhookVerifier.TIMESTAMP_HEADER).isEqualTo("X-Bank-Timestamp");
    }

    @Test
    void rejectsTamperedBody() {
        assertThat(verifier.isValid(TIMESTAMP, SIGNATURE, BODY.replace("SUCCEEDED", "FAILED"))).isFalse();
    }

    @Test
    void rejectsTimestampOutsideTolerance() {
        BankWebhookVerifier strict = new BankWebhookVerifier(SECRET, "", Duration.ofMinutes(5));

        assertThat(strict.isValid(TIMESTAMP, SIGNATURE, BODY)).isFalse();
    }

    @Test
    void rejectsMalformedHeaders() {
        String now = String.valueOf(Instant.now().getEpochSecond());

        assertThat(verifier.isValid(null, SIGNATURE, BODY)).isFalse();
        assertThat(verifier.isValid("not-a-number", SIGNATURE, BODY)).isFalse();
        assertThat(verifier.isValid(now, null, BODY)).isFalse();
        assertThat(verifier.isValid(now, "md5=abc", BODY)).isFalse();
        assertThat(verifier.isValid(now, "sha256=not-hex", BODY)).isFalse();
    }

    @Test
    void duringRotation_acceptsCurrentAndPreviousSecret() {
        BankWebhookVerifier newSecretOnBankSim = new BankWebhookVerifier("other-secret", SECRET, LONG_TOLERANCE);
        BankWebhookVerifier oldSecretOnBankSim = new BankWebhookVerifier(SECRET, "other-secret", LONG_TOLERANCE);

        assertThat(newSecretOnBankSim.isValid(TIMESTAMP, SIGNATURE, BODY)).isTrue();
        assertThat(oldSecretOnBankSim.isValid(TIMESTAMP, SIGNATURE, BODY)).isTrue();
    }

    @Test
    void afterRotation_rejectsTheDroppedSecret() {
        BankWebhookVerifier rotated = new BankWebhookVerifier("other-secret", "", LONG_TOLERANCE);

        assertThat(rotated.isValid(TIMESTAMP, SIGNATURE, BODY)).isFalse();
    }
}
