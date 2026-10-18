package com.waller.wallet_platform.bank.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

class BankWebhookLimitFilterTest {

    private static final int MAX_REQUESTS = 2;
    private static final int MAX_BODY_BYTES = 10;

    private final BankWebhookLimitFilter filter =
            new BankWebhookLimitFilter(MAX_REQUESTS, Duration.ofMinutes(1), DataSize.ofBytes(MAX_BODY_BYTES));

    @Test
    void bodyWithinLimit_isPassedOnUnchanged() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(webhook("0123456789"), chain);

        assertThat(response.getStatus()).isEqualTo(200);
        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        assertThat(new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
    }

    @Test
    void bodyOverLimit_is413AndNeverReachesTheController() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(webhook("01234567890"), chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void bodyOverLimitWithoutContentLength_is413() throws Exception {
        // Like a chunked request: the size is only known by reading
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/bank") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent("01234567890".getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();

        assertThat(run(request, chain).getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void overRateLimit_is429WithRetryAfter() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            assertThat(run(webhook("{}"), new MockFilterChain()).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse limited = run(webhook("{}"), new MockFilterChain());

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void otherPaths_areNotLimited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfer/create");
        request.setContent("01234567890".getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();

        assertThat(run(request, chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest webhook(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/bank");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
