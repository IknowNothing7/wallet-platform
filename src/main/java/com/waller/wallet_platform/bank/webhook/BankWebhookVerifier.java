package com.waller.wallet_platform.bank.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
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

    private final List<SecretKeySpec> keys;
    private final Duration tolerance;

    // A non-blank previous secret is accepted too, so during a rotation bank-sim and wallet-service can switch
    // separately: give wallet-service the new secret with the old one as previous, then switch bank-sim, then drop it
    public BankWebhookVerifier(
            @Value("${bank.webhook.secret}") String secret,
            @Value("${bank.webhook.previous-secret:}") String previousSecret,
            @Value("${bank.webhook.tolerance:PT5M}") Duration tolerance) {
        this.keys = Stream.of(secret, previousSecret)
                .filter(s -> !s.isBlank())
                .map(s -> new SecretKeySpec(s.getBytes(StandardCharsets.UTF_8), ALGORITHM))
                .toList();
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
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        String signed = timestamp + "." + body;
        boolean valid = false;
        for (SecretKeySpec key : keys) {
            // Constant time, so the signature can't be guessed byte by byte from response times. Every key is
            // checked, not just until a match, so timing doesn't reveal which secret was used either
            valid |= MessageDigest.isEqual(sign(key, signed), actual);
        }
        return valid;
    }

    private byte[] sign(SecretKeySpec key, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot verify webhook signature", e);
        }
    }
}
