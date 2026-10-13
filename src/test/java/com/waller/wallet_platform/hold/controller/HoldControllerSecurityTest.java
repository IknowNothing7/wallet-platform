package com.waller.wallet_platform.hold.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.config.SecurityConfig;
import com.waller.wallet_platform.hold.dto.HoldCaptureRequest;
import com.waller.wallet_platform.hold.dto.HoldDto;
import com.waller.wallet_platform.hold.dto.HoldRequest;
import com.waller.wallet_platform.hold.service.HoldService;
import com.waller.wallet_platform.security.AccessTokenDenylist;
import com.waller.wallet_platform.security.JwtAuthenticationFilter;
import com.waller.wallet_platform.security.JwtTokenProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

// Checks who may call which hold endpoint. The service is mocked; only the security wiring is under test.
@WebMvcTest(HoldController.class)
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class })
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost")
class HoldControllerSecurityTest {

    private static final String HOLD_BODY =
            "{\"accountId\":1,\"amount\":100,\"currency\":\"USD\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}";
    private static final String CAPTURE_BODY = "{\"destinationAccountId\":2}";

    private static final HoldDto HOLD = HoldDto.builder().id(10L).accountId(1L).amount(100L).currency("USD").build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HoldService holdService;

    // Dependencies of the real JwtAuthenticationFilter; requests here carry no token, so it just passes them on
    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AccessTokenDenylist accessTokenDenylist;

    // ---- unauthenticated ----

    @Test
    void noUser_getHold_is401() throws Exception {
        mockMvc.perform(get("/hold/10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(holdService);
    }

    @Test
    void noUser_createHold_is401() throws Exception {
        mockMvc.perform(post("/hold/create").contentType(MediaType.APPLICATION_JSON).content(HOLD_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(holdService);
    }

    // ---- create: MERCHANT only ----

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchant_createHold_is201() throws Exception {
        when(holdService.createHold(any(HoldRequest.class))).thenReturn(HOLD);

        mockMvc.perform(post("/hold/create").contentType(MediaType.APPLICATION_JSON).content(HOLD_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.id").value(10));
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_createHold_is403() throws Exception {
        mockMvc.perform(post("/hold/create").contentType(MediaType.APPLICATION_JSON).content(HOLD_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(holdService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_createHold_is403() throws Exception {
        mockMvc.perform(post("/hold/create").contentType(MediaType.APPLICATION_JSON).content(HOLD_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(holdService);
    }

    // ---- capture: MERCHANT only ----

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchant_captureHold_is200() throws Exception {
        when(holdService.captureHold(eq(10L), any(HoldCaptureRequest.class))).thenReturn(HOLD);

        mockMvc.perform(post("/hold/10/capture").contentType(MediaType.APPLICATION_JSON).content(CAPTURE_BODY))
                .andExpect(status().isOk());

        verify(holdService).captureHold(eq(10L), any(HoldCaptureRequest.class));
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_captureHold_is403() throws Exception {
        mockMvc.perform(post("/hold/10/capture").contentType(MediaType.APPLICATION_JSON).content(CAPTURE_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(holdService);
    }

    // ---- release: MERCHANT only ----

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchant_releaseHold_is200() throws Exception {
        when(holdService.releaseHold(10L)).thenReturn(HOLD);

        mockMvc.perform(post("/hold/10/release"))
                .andExpect(status().isOk());

        verify(holdService).releaseHold(10L);
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_releaseHold_is403() throws Exception {
        mockMvc.perform(post("/hold/10/release"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(holdService);
    }

    // ---- expire: ADMIN only ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_expireHold_is200() throws Exception {
        when(holdService.expireHold(10L)).thenReturn(HOLD);

        mockMvc.perform(post("/hold/10/expire"))
                .andExpect(status().isOk());

        verify(holdService).expireHold(10L);
    }

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchant_expireHold_is403() throws Exception {
        mockMvc.perform(post("/hold/10/expire"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(holdService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_expireHold_is403() throws Exception {
        mockMvc.perform(post("/hold/10/expire"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(holdService);
    }

    // ---- reads: any authenticated user; ownership is checked in the service ----

    @Test
    @WithMockUser(roles = "USER")
    void user_getHold_reachesService() throws Exception {
        when(holdService.getHold(10L)).thenReturn(HOLD);

        mockMvc.perform(get("/hold/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.id").value(10));
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_getAccountHolds_reachesService() throws Exception {
        when(holdService.getAccountHolds(1L, 0, 20)).thenReturn(new PageResponse<>(List.of(HOLD), 0, 20, 1, 1));

        mockMvc.perform(get("/hold/account/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.content[0].id").value(10));
    }
}
