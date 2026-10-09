package com.waller.wallet_platform.security;

import java.io.IOException;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

// Limits requests per client IP to the public auth endpoints (login, register, refresh, logout).
// Behind a reverse proxy, set server.forward-headers-strategy so getRemoteAddr() is the real client IP.
@Component
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/api/auth/";
    private static final String TOO_MANY_REQUESTS_BODY =
            "{\"message\":\"Too many requests, try again later\",\"payload\":null,\"success\":false}";

    private final RateLimiter limiter;

    public AuthRateLimitFilter(
            @Value("${auth.rate-limit.ip.max-requests:20}") int maxRequests,
            @Value("${auth.rate-limit.ip.window:PT1M}") Duration window) {
        this.limiter = new RateLimiter(maxRequests, window);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().startsWith(request.getContextPath() + AUTH_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = request.getRemoteAddr();
        if (!limiter.tryAcquire(clientIp)) {
            log.warn("Auth rate limit exceeded for {}", clientIp);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.secondsUntilReset(clientIp)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(TOO_MANY_REQUESTS_BODY);
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Scheduled(fixedDelayString = "${auth.rate-limit.cleanup-interval:PT5M}")
    public void evictExpired() {
        limiter.evictExpired();
    }

}
