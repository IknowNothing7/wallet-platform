package com.waller.wallet_platform.outbox.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.waller.wallet_platform.common.web.PageResponse;
import com.waller.wallet_platform.config.SecurityConfig;
import com.waller.wallet_platform.outbox.dto.OutboxEventDto;
import com.waller.wallet_platform.outbox.service.OutboxEventService;
import com.waller.wallet_platform.security.AccessTokenDenylist;
import com.waller.wallet_platform.security.JwtAuthenticationFilter;
import com.waller.wallet_platform.security.JwtTokenProvider;

// Every /outbox endpoint is ADMIN only. The service is mocked; only the security wiring is under test.
@WebMvcTest(OutboxEventController.class)
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class })
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost")
class OutboxEventControllerSecurityTest {

    private static final OutboxEventDto EVENT = OutboxEventDto.builder().id(1L).build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutboxEventService outboxEventService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AccessTokenDenylist accessTokenDenylist;

    @Test
    void noUser_getEvents_is401() throws Exception {
        mockMvc.perform(get("/api/outbox-events"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(outboxEventService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_getEvents_is403() throws Exception {
        mockMvc.perform(get("/api/outbox-events"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(outboxEventService);
    }

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchant_getEvents_is403() throws Exception {
        mockMvc.perform(get("/api/outbox-events"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_getEvent_is403() throws Exception {
        mockMvc.perform(get("/api/outbox-events/1"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_retryEvent_is403() throws Exception {
        mockMvc.perform(post("/api/outbox-events/1/retry"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_getEvents_reachesService() throws Exception {
        when(outboxEventService.getEvents(null, 0, 20)).thenReturn(new PageResponse<>(List.of(EVENT), 0, 20, 1, 1));

        mockMvc.perform(get("/api/outbox-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.content[0].id").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_retryEvent_reachesService() throws Exception {
        when(outboxEventService.retryEvent(1L)).thenReturn(EVENT);

        mockMvc.perform(post("/api/outbox-events/1/retry"))
                .andExpect(status().isOk());

        verify(outboxEventService).retryEvent(1L);
    }
}
