package com.waller.wallet_platform.bank.webhook;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.waller.wallet_platform.support.IntegrationTest;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

class BankWebhookControllerTest extends IntegrationTest {

    @Value("${bank.webhook.secret}")
    private String secret;

    @Autowired
    private BankWebhookDeadLetterRepository deadLetterRepository;

    private final long timestamp = Instant.now().getEpochSecond();

    @Test
    void signedButUnreadableBody_isStoredAsDeadLetterAndAcknowledged() throws Exception {
        String body = "{\"reference\": not json";

        post(body, sign(body)).then().statusCode(200);

        assertThat(deadLetterRepository.findAll()).singleElement().satisfies(deadLetter -> {
            assertThat(deadLetter.getBody()).isEqualTo(body);
            assertThat(deadLetter.getReason()).isNotBlank();
            assertThat(deadLetter.getReceivedAt()).isNotNull();
        });
    }

    @Test
    void invalidSignature_isRejectedAndNotStored() {
        post("{\"reference\": not json", "sha256=00").then().statusCode(401);

        assertThat(deadLetterRepository.count()).isZero();
    }

    @Test
    void bodyOverMaxSize_isRejectedBeforeTheSignatureCheck() throws Exception {
        String body = "x".repeat(16 * 1024 + 1);

        post(body, sign(body)).then().statusCode(413);

        assertThat(deadLetterRepository.count()).isZero();
    }

    private Response post(String body, String signature) {
        return given()
                .contentType(ContentType.JSON)
                .header(BankWebhookVerifier.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .header(BankWebhookVerifier.SIGNATURE_HEADER, signature)
                .body(body)
        .when()
                .post("/webhooks/bank");
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(digest);
    }
}
