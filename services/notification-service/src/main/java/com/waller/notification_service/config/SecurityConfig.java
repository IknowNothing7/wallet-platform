package com.waller.notification_service.config;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))
                .build();
    }

    /**
     * Keys are fetched from wallet-service on first use and cached. A token with an unknown "kid" (key rotation)
     * triggers a refetch, at most once per refresh interval, so made-up kids can't flood wallet-service. A failed
     * fetch keeps the last keys in use: an outage of wallet-service does not stop token validation.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${notification.jwks.cache-ttl:PT5M}") Duration cacheTtl,
            @Value("${notification.jwks.min-refresh-interval:PT30S}") Duration minRefreshInterval)
            throws MalformedURLException {
        JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(URI.create(jwkSetUri).toURL())
                .cache(cacheTtl.toMillis(), JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT)
                .rateLimited(minRefreshInterval.toMillis())
                .outageTolerantForever()
                .build();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys).build();
        decoder.setJwtValidator(jwtValidator());
        return decoder;
    }

    /** The default checks (expiry, not-before) plus a usable "user_id" claim. */
    public static OAuth2TokenValidator<Jwt> jwtValidator() {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), new UserIdClaimValidator());
    }
}
