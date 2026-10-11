package com.waller.bank_sim.webhook.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import com.waller.bank_sim.config.WebhookProperties;

/**
 * Signs "<timestamp>.<raw body>" with HMAC-SHA256. Including the timestamp lets the receiver reject
 * replays older than its tolerance window; it must verify against the raw bytes, not re-serialized JSON.
 */
@Component
public class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-Bank-Signature";
    public static final String TIMESTAMP_HEADER = "X-Bank-Timestamp";
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public WebhookSigner(WebhookProperties properties) {
        this.key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign webhook", e);
        }
    }
}
