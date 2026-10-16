package com.waller.wallet_platform.bank.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks bank-sim's HMAC-SHA256 over "<timestamp>.<raw body>". Must be given the raw request body:
 * re-serialized JSON can differ byte for byte and would never match.
 */
@Component
public class BankWebhookVerifier {

    public static final String SIGNATURE_HEADER = "X-Bank-Signature";
    public static final String TIMESTAMP_HEADER = "X-Bank-Timestamp";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final SecretKeySpec key;
    private final Duration tolerance;

    public BankWebhookVerifier(
            @Value("${bank.webhook.secret}") String secret,
            @Value("${bank.webhook.tolerance:PT5M}") Duration tolerance) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.tolerance = tolerance;
    }

    public boolean isValid(String timestamp, String signature, String body) {
        if (timestamp == null || signature == null || !signature.startsWith(PREFIX)) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        // Rejects replays of an old, validly signed body
        Duration age = Duration.between(Instant.ofEpochSecond(seconds), Instant.now()).abs();
        if (age.compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = sign(timestamp + "." + body);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // Constant time, so the signature can't be guessed byte by byte from response times
        return MessageDigest.isEqual(expected, actual);
        // TODO: Accept a second (previous) secret during rotation, so bank-sim and wallet-service can switch separately
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot verify webhook signature", e);
        }
    }
}
