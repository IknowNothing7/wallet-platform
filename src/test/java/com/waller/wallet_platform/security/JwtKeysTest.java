package com.waller.wallet_platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class JwtKeysTest {

    @Test
    void configuredKey_sameKeyGetsTheSameKeyIdAcrossRestarts() throws Exception {
        String pem = pem();

        JwtKeys first = new JwtKeys(pem);
        JwtKeys second = new JwtKeys(pem);

        assertThat(second.getKeyId()).isEqualTo(first.getKeyId());
        assertThat(second.getPublicKey()).isEqualTo(first.getPublicKey());
    }

    @Test
    void configuredKey_acceptsTheBase64BodyWithoutPemArmour() throws Exception {
        String pem = pem();
        String bodyOnly = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");

        assertThat(new JwtKeys(bodyOnly).getKeyId()).isEqualTo(new JwtKeys(pem).getKeyId());
    }

    @Test
    void noConfiguredKey_generatesOne() {
        JwtKeys generated = new JwtKeys("");

        assertThat(generated.getPrivateKey()).isNotNull();
        assertThat(generated.getKeyId()).isNotBlank().isNotEqualTo(new JwtKeys("").getKeyId());
    }

    @Test
    void invalidKey_failsStartup() {
        assertThatThrownBy(() -> new JwtKeys("not a key")).isInstanceOf(IllegalStateException.class);
    }

    private static String pem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----\n";
    }
}
