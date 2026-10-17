package com.waller.bank_sim.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.waller.bank_sim.config.WebhookProperties;

class WebhookSignerTest {

    // Same vector as wallet-service's BankWebhookVerifierTest: if either side changes the signed
    // format or encoding, both tests must change together
    private static final String SECRET = "test-secret";
    private static final long TIMESTAMP = 1_700_000_000L;
    private static final String BODY = "{\"reference\":\"bsim_1\",\"status\":\"SUCCEEDED\"}";
    private static final String SIGNATURE =
            "sha256=3099092c33360dfa720dd1120a0ff4f10451b3a951f36177bbbd805fede7fd02";

    private final WebhookSigner signer = new WebhookSigner(properties(SECRET));

    @Test
    void signsTimestampAndBodyInTheFormatWalletServiceVerifies() {
        assertThat(signer.sign(TIMESTAMP, BODY)).isEqualTo(SIGNATURE);
    }

    @Test
    void headerNamesMatchWalletService() {
        assertThat(WebhookSigner.SIGNATURE_HEADER).isEqualTo("X-Bank-Signature");
        assertThat(WebhookSigner.TIMESTAMP_HEADER).isEqualTo("X-Bank-Timestamp");
    }

    @Test
    void signatureCoversTheTimestamp() {
        assertThat(signer.sign(TIMESTAMP + 1, BODY)).isNotEqualTo(SIGNATURE);
    }

    @Test
    void signatureDependsOnTheSecret() {
        assertThat(new WebhookSigner(properties("other-secret")).sign(TIMESTAMP, BODY)).isNotEqualTo(SIGNATURE);
    }

    private static WebhookProperties properties(String secret) {
        return new WebhookProperties("http://unused", secret, Duration.ZERO, 1,
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1));
    }
}
