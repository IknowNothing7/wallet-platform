package com.waller.notification_service.notification.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.waller.notification_service.common.web.PageResponse;
import com.waller.notification_service.config.SecurityConfig;
import com.waller.notification_service.notification.dto.NotificationDto;
import com.waller.notification_service.notification.dto.UnreadCountDto;
import com.waller.notification_service.notification.service.NotificationService;

/**
 * The real security filter chain with real RS256 bearer tokens. Only the JWKS lookup is replaced: the decoder
 * verifies against a key generated here instead of fetching wallet-service's key set.
 */
@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, NotificationControllerTest.WebSecurity.class})
class NotificationControllerTest {

    // Without spring-boot-security-test on the classpath @WebMvcTest does not bring in Boot's web security
    // auto-configuration, so the HttpSecurity bean SecurityConfig needs is enabled here
    @EnableWebSecurity
    static class WebSecurity {
    }

    private static final long ALICE = 7L;
    private static final long BOB = 8L;

    private static RSAKey signingKey;
    private static RSAKey otherKey;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    // Replaces SecurityConfig's JWKS-backed decoder; the validators are SecurityConfig's own
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeAll
    static void generateKeys() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("test").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("test").generate();
    }

    @BeforeEach
    void verifyAgainstTestKey() throws JOSEException {
        NimbusJwtDecoder real = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        real.setJwtValidator(SecurityConfig.jwtValidator());
        when(jwtDecoder.decode(anyString())).thenAnswer(inv -> real.decode(inv.getArgument(0)));
    }

    // --- authentication ---

    @Test
    void everyEndpoint_withoutToken_is401() throws Exception {
        for (MockHttpServletRequestBuilder request : allEndpoints()) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(notificationService);
    }

    @Test
    void tokenSignedWithAnotherKey_is401() throws Exception {
        String forged = token(otherKey, claims(ALICE).build());
        for (MockHttpServletRequestBuilder request : allEndpoints()) {
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(notificationService);
    }

    @Test
    void expiredToken_is401() throws Exception {
        String expired = token(signingKey, claims(ALICE)
                .issueTime(Date.from(Instant.now().minus(Duration.ofHours(2))))
                .expirationTime(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .build());

        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notificationService);
    }

    @Test
    void malformedToken_is401() throws Exception {
        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notificationService);
    }

    // Every endpoint scopes by user_id, so a validly signed token without a usable one is rejected up front
    @Test
    void tokenWithoutUserIdClaim_is401() throws Exception {
        String noUserId = token(signingKey, new JWTClaimsSet.Builder()
                .subject("alice@email.com")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .build());
        for (MockHttpServletRequestBuilder request : allEndpoints()) {
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + noUserId))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(notificationService);
    }

    @Test
    void tokenWithStringUserId_is401() throws Exception {
        String stringUserId = token(signingKey, claims(ALICE).claim("user_id", "7").build());

        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + stringUserId))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notificationService);
    }

    @Test
    void tokenWithNonPositiveUserId_is401() throws Exception {
        String zeroUserId = token(signingKey, claims(0L).build());

        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + zeroUserId))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notificationService);
    }

    // --- GET /notifications ---

    @Test
    void list_usesUserIdClaimAndDefaultPaging() throws Exception {
        NotificationDto dto = new NotificationDto(11L, Instant.parse("2026-01-01T10:00:00Z"), "TransferCompleted",
                "Transfer completed", "You sent 10.00 USD", null);
        when(notificationService.getNotifications(ALICE, 0, 20))
                .thenReturn(new PageResponse<>(List.of(dto), 0, 20, 1, 1));

        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.payload.content[0].id").value(11))
                .andExpect(jsonPath("$.payload.content[0].title").value("Transfer completed"))
                .andExpect(jsonPath("$.payload.content[0].eventType").value("TransferCompleted"))
                .andExpect(jsonPath("$.payload.totalElements").value(1))
                .andExpect(jsonPath("$.payload.page").value(0))
                .andExpect(jsonPath("$.payload.size").value(20));

        verify(notificationService).getNotifications(ALICE, 0, 20);
    }

    @Test
    void list_passesPagingParams() throws Exception {
        when(notificationService.getNotifications(ALICE, 2, 5)).thenReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/notifications").param("page", "2").param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk());

        verify(notificationService).getNotifications(ALICE, 2, 5);
    }

    @Test
    void list_userIdComesFromTheClaimNotTheSubject() throws Exception {
        when(notificationService.getNotifications(BOB, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        // Subject says alice, but the user_id claim is what scopes the query
        String token = token(signingKey, claims(BOB).subject("alice@email.com").build());

        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        verify(notificationService).getNotifications(BOB, 0, 20);
        verify(notificationService, never()).getNotifications(ALICE, 0, 20);
    }

    @Test
    void list_sizeAboveMax_is400() throws Exception {
        mockMvc.perform(get("/notifications").param("size", "101").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isBadRequest());
        verify(notificationService, never()).getNotifications(anyLong(), anyInt(), anyInt());
    }

    @Test
    void list_sizeZero_is400() throws Exception {
        mockMvc.perform(get("/notifications").param("size", "0").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isBadRequest());
        verify(notificationService, never()).getNotifications(anyLong(), anyInt(), anyInt());
    }

    @Test
    void list_negativePage_is400() throws Exception {
        mockMvc.perform(get("/notifications").param("page", "-1").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isBadRequest());
        verify(notificationService, never()).getNotifications(anyLong(), anyInt(), anyInt());
    }

    // --- GET /notifications/unread-count ---

    @Test
    void unreadCount_isForTheTokensUser() throws Exception {
        when(notificationService.getUnreadCount(ALICE)).thenReturn(new UnreadCountDto(3));

        mockMvc.perform(get("/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.payload.unread").value(3));

        verify(notificationService).getUnreadCount(ALICE);
    }

    // --- POST /notifications/{id}/read ---

    @Test
    void markRead_passesTokensUserAndPathId() throws Exception {
        Instant readAt = Instant.parse("2026-01-02T00:00:00Z");
        when(notificationService.markRead(ALICE, 42L)).thenReturn(new NotificationDto(42L,
                Instant.parse("2026-01-01T00:00:00Z"), "TransferCompleted", "t", "b", readAt));

        mockMvc.perform(post("/notifications/42/read").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.id").value(42))
                .andExpect(jsonPath("$.payload.readAt").exists());

        verify(notificationService).markRead(ALICE, 42L);
    }

    @Test
    void markRead_someoneElsesNotification_is404() throws Exception {
        // The service scopes the lookup by user, so bob's id is "not found" for alice
        when(notificationService.markRead(ALICE, 42L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));

        mockMvc.perform(post("/notifications/42/read").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isNotFound());
    }

    // --- POST /notifications/read-all ---

    @Test
    void readAll_is204AndScopedToTheTokensUser() throws Exception {
        mockMvc.perform(post("/notifications/read-all").header(HttpHeaders.AUTHORIZATION, bearer(BOB)))
                .andExpect(status().isNoContent());

        verify(notificationService).markAllRead(BOB);
        verify(notificationService, never()).markAllRead(ALICE);
    }

    private static List<MockHttpServletRequestBuilder> allEndpoints() {
        return List.of(
                get("/notifications"),
                get("/notifications/unread-count"),
                post("/notifications/1/read"),
                post("/notifications/read-all"));
    }

    private static String bearer(long userId) throws JOSEException {
        return "Bearer " + token(signingKey, claims(userId).build());
    }

    // Shaped like wallet-service's tokens: RS256, kid header, user_id claim
    private static JWTClaimsSet.Builder claims(long userId) {
        return new JWTClaimsSet.Builder()
                .subject("user" + userId + "@email.com")
                .claim("user_id", userId)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))));
    }

    private static String token(RSAKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
