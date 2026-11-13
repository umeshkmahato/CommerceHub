package com.commercehub.user.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JWT issuance & validation (jjwt 0.12.x API).
 *
 * Design decisions:
 *  - HS256 symmetric signing: simple, sufficient while token TTL is short
 *    and only our services verify. RS256 (asymmetric) is the upgrade path
 *    if third parties ever need to verify without the secret.
 *  - Access token carries userId + roles as claims → downstream services
 *    authorize WITHOUT calling user-service (stateless scalability).
 *  - Secret comes from env (JWT_SECRET); NEVER hardcoded. Min 32 bytes —
 *    enforced in constructor so misconfiguration fails fast at boot.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlMinutes;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.access-ttl-minutes:15}") long accessTtlMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes (256 bits) for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtlMinutes = accessTtlMinutes;
    }

    /** Issue a short-lived access token. */
    public String generateAccessToken(Long userId, String email, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("roles", roles)
                .claim("type", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtlMinutes, ChronoUnit.MINUTES)))
                // Pin HS256: jjwt 0.12 otherwise auto-selects the strongest
                // alg the key size allows (HS384 needs >=384-bit secrets,
                // which would surprise operators with WeakKeyException).
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Validate signature + expiry; returns claims or throws JwtException. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long userIdFrom(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    @SuppressWarnings("unchecked")
    public List<String> rolesFrom(String token) {
        Object roles = parse(token).get("roles");
        return roles instanceof List ? (List<String>) roles : List.of();
    }

    public boolean isAccessToken(String token) {
        try {
            return "access".equals(parse(token).get("type"));
        } catch (Exception e) {
            return false;
        }
    }
}
