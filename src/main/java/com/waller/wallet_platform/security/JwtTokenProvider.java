package com.waller.wallet_platform.security;

import com.waller.wallet_platform.model.constants.AuthenticationConstants;
import com.waller.wallet_platform.model.entites.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;


@Component
@Slf4j 
@RequiredArgsConstructor 
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.lifetime}")
    private long lifetime;

    public String generateToken(AppUser user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(AuthenticationConstants.USER_ID, user.getId())
                .claim(AuthenticationConstants.USERNAME, user.getName())
                .claim(AuthenticationConstants.ROLE, List.of(user.getRole().name()))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + lifetime))
                .signWith(signingKey())
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    public String getUsername(String token) {
        Claims claims = getAllClaimsFromToken(token);
        return claims.get(AuthenticationConstants.USERNAME, String.class);
    }

    public String getUserId(String token) {
        Claims claims = getAllClaimsFromToken(token);
        return String.valueOf(claims.get(AuthenticationConstants.USER_ID, Number.class));
    }

    public List<String> getRoles(String token) {
        List<?> roles = getAllClaimsFromToken(token).get(AuthenticationConstants.ROLE, List.class);
        return roles == null ? List.of() : roles.stream().map(String::valueOf).toList();
    }

    private Claims getAllClaimsFromToken(String token) {
        return parseClaims(token);
    }
}

