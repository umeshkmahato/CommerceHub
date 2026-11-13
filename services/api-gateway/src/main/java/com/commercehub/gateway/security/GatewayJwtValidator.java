package com.commercehub.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT validation for the gateway — mirrors user-service's JwtService.
 *
 * IMPORTANT: the secret must match user-service's JWT_SECRET. In a real
 * deployment this would be a shared secret from a vault, or better, RS256
 * with the gateway holding only the PUBLIC key (services sign, gateway
 * verifies — no shared symmetric secret at all).
 */
@Component
public class GatewayJwtValidator {

    private final SecretKey key;

    public GatewayJwtValidator(@Value("${jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be >= 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** Returns claims if valid, throws on bad/expired signature. */
    public Claims validate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @SuppressWarnings("unchecked")
    public List<String> roles(Claims claims) {
        Object r = claims.get("roles");
        return r instanceof List ? (List<String>) r : List.of();
    }
}
