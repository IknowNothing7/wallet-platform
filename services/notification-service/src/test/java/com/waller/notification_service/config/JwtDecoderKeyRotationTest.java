package com.waller.notification_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

// The decoder against a stand-in for wallet-service's /.well-known/jwks.json
class JwtDecoderKeyRotationTest {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration SHORT_REFRESH_INTERVAL = Duration.ofMillis(50);

    private final AtomicReference<JWKSet> served = new AtomicReference<>();
    private final AtomicBoolean down = new AtomicBoolean();
    private final AtomicInteger fetches = new AtomicInteger();
    private HttpServer server;
    private RSAKey oldKey;
    private RSAKey newKey;

    @BeforeEach
    void setUp() throws IOException, JOSEException {
        oldKey = new RSAKeyGenerator(2048).keyID("old").generate();
        newKey = new RSAKeyGenerator(2048).keyID("new").generate();
        served.set(new JWKSet(oldKey.toPublicJWK()));

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            fetches.incrementAndGet();
            if (down.get()) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                byte[] body = served.get().toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void verifiesTokenSignedWithThePublishedKey() throws Exception {
        assertThat(decoder(SHORT_REFRESH_INTERVAL).decode(token(oldKey)).<Number>getClaim("user_id"))
                .isEqualTo(7L);
    }

    @Test
    void unknownKid_refetchesAndPicksUpTheRotatedKey() throws Exception {
        JwtDecoder decoder = decoder(SHORT_REFRESH_INTERVAL);
        decoder.decode(token(oldKey));

        served.set(new JWKSet(List.of(newKey.toPublicJWK(), oldKey.toPublicJWK())));
        Thread.sleep(SHORT_REFRESH_INTERVAL.toMillis() * 2);

        assertThat(decoder.decode(token(newKey)).getSubject()).isEqualTo("alice@email.com");
        assertThat(decoder.decode(token(oldKey)).getSubject()).isEqualTo("alice@email.com");
    }

    @Test
    void unknownKidDuringOutage_keepsTheKeysThatStillWork() throws Exception {
        JwtDecoder decoder = decoder(SHORT_REFRESH_INTERVAL);
        decoder.decode(token(oldKey));

        down.set(true);
        Thread.sleep(SHORT_REFRESH_INTERVAL.toMillis() * 2);
        assertThatThrownBy(() -> decoder.decode(token(newKey))).isInstanceOf(JwtException.class);

        assertThat(decoder.decode(token(oldKey)).getSubject()).isEqualTo("alice@email.com");
    }

    @Test
    void madeUpKids_doNotRefetchOnEveryRequest() throws Exception {
        JwtDecoder decoder = decoder(Duration.ofMinutes(1));
        decoder.decode(token(oldKey));
        int afterFirstFetch = fetches.get();

        for (int i = 0; i < 10; i++) {
            RSAKey attacker = new RSAKeyGenerator(2048).keyID("made-up-" + i).generate();
            assertThatThrownBy(() -> decoder.decode(token(attacker))).isInstanceOf(JwtException.class);
        }

        // Nimbus allows a couple of refreshes per interval, not one per token
        assertThat(fetches.get() - afterFirstFetch).isLessThanOrEqualTo(2);
    }

    private JwtDecoder decoder(Duration minRefreshInterval) throws Exception {
        String uri = "http://localhost:" + server.getAddress().getPort() + "/.well-known/jwks.json";
        return new SecurityConfig().jwtDecoder(uri, CACHE_TTL, minRefreshInterval);
    }

    // Shaped like wallet-service's tokens: RS256, kid header, user_id claim
    private static String token(RSAKey key) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .subject("alice@email.com")
                        .claim("user_id", 7L)
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
