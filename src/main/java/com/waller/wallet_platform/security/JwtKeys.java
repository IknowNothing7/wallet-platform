package com.waller.wallet_platform.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The RSA key access tokens are signed with. Other services (notification-service) verify them with the public
 * half from /.well-known/jwks.json, so they can check tokens but never mint them.
 */
@Component
@Slf4j
@Getter
public class JwtKeys {

    private static final int GENERATED_KEY_BITS = 2048;

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    // RFC 7638 thumbprint: the same key always gets the same id, so a restart doesn't look like a rotation
    private final String keyId;

    public JwtKeys(@Value("${jwt.private-key:}") String privateKeyPem) {
        try {
            if (privateKeyPem.isBlank()) {
                // Fine for one local instance; tokens die on restart and instances wouldn't accept each other's
                log.warn("jwt.private-key is not set: using a generated key, tokens won't survive a restart");
                var pair = KeyPairGenerator.getInstance("RSA");
                pair.initialize(GENERATED_KEY_BITS);
                var generated = pair.generateKeyPair();
                this.privateKey = (RSAPrivateKey) generated.getPrivate();
                this.publicKey = (RSAPublicKey) generated.getPublic();
            } else {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                this.privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(decodePem(privateKeyPem)));
                if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                    throw new IllegalStateException("jwt.private-key must be a PKCS#8 RSA key with CRT parameters");
                }
                this.publicKey = (RSAPublicKey) factory.generatePublic(
                        new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            }
            this.keyId = thumbprint(publicKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot load jwt.private-key", e);
        }
    }

    /** The public key as a JWK, in the shape /.well-known/jwks.json serves. */
    public Map<String, String> publicJwk() {
        Map<String, String> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", keyId);
        jwk.put("n", base64Url(publicKey.getModulus()));
        jwk.put("e", base64Url(publicKey.getPublicExponent()));
        return jwk;
    }

    private static String thumbprint(RSAPublicKey key) throws GeneralSecurityException {
        // Required members only, in lexicographic order, no whitespace (RFC 7638 section 3.2)
        String json = "{\"e\":\"" + base64Url(key.getPublicExponent())
                + "\",\"kty\":\"RSA\",\"n\":\"" + base64Url(key.getModulus()) + "\"}";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    // Unsigned big-endian, as JWK requires: BigInteger adds a leading zero byte when the top bit is set
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Accepts the PEM file contents or just its base64 body (easier to put in an environment variable)
    private static byte[] decodePem(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
