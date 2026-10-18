package com.waller.wallet_platform.bank.webhook;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import com.waller.wallet_platform.security.RateLimiter;

/**
 * POST /webhooks/bank is public and its signature can only be checked once the whole body is read, so it gets
 * limits before that: requests per client IP, and body size (read at most max-body-size + 1 bytes, never more).
 * Both answer an error that bank-sim retries with backoff. The IP limit must stay well above the real webhook
 * rate, since every legitimate request comes from bank-sim's address.
 */
@Component
@Slf4j
public class BankWebhookLimitFilter extends OncePerRequestFilter {

    private static final String WEBHOOK_PATH = "/webhooks/bank";
    private static final String TOO_MANY_REQUESTS_BODY =
            "{\"message\":\"Too many requests, try again later\",\"payload\":null,\"success\":false}";
    private static final String TOO_LARGE_BODY =
            "{\"message\":\"Request body too large\",\"payload\":null,\"success\":false}";

    private final RateLimiter limiter;
    private final int maxBodyBytes;

    public BankWebhookLimitFilter(
            @Value("${bank.webhook.rate-limit.max-requests:600}") int maxRequests,
            @Value("${bank.webhook.rate-limit.window:PT1M}") Duration window,
            @Value("${bank.webhook.max-body-size:16KB}") DataSize maxBodySize) {
        this.limiter = new RateLimiter(maxRequests, window);
        this.maxBodyBytes = Math.toIntExact(maxBodySize.toBytes());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().equals(request.getContextPath() + WEBHOOK_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = request.getRemoteAddr();
        if (!limiter.tryAcquire(clientIp)) {
            log.warn("Bank webhook rate limit exceeded for {}", clientIp);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.secondsUntilReset(clientIp)));
            reject(response, HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_REQUESTS_BODY);
            return;
        }
        // Content-Length can be missing (chunked) or wrong, so the read below is the real check
        if (request.getContentLengthLong() > maxBodyBytes) {
            rejectTooLarge(response, clientIp);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            rejectTooLarge(response, clientIp);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    @Scheduled(fixedDelayString = "${auth.rate-limit.cleanup-interval:PT5M}")
    public void evictExpired() {
        limiter.evictExpired();
    }

    private void rejectTooLarge(HttpServletResponse response, String clientIp) throws IOException {
        log.warn("Rejected bank webhook from {}: body larger than {} bytes", clientIp, maxBodyBytes);
        reject(response, HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE_BODY);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }

    // The body has already been read from the socket; hand the same bytes to the controller
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
