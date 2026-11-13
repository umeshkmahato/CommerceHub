package com.commercehub.user;

import com.commercehub.user.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT unit tests — no Spring context needed; JwtService is a plain class.
 */
class JwtServiceTest {

    // 32+ byte test secret (NOT a real credential — test-only value)
    private static final String SECRET =
            "test-secret-key-that-is-at-least-32-bytes-long!!";

    private final JwtService jwt = new JwtService(SECRET, 15);

    @Test
    void issuedToken_parsesWithCorrectClaims() {
        String token = jwt.generateAccessToken(42L, "a@b.com", List.of("CUSTOMER"));
        Claims claims = jwt.parse(token);

        assertEquals("42", claims.getSubject());
        assertEquals("a@b.com", claims.get("email"));
        assertEquals("access", claims.get("type"));
        assertEquals(List.of("CUSTOMER"), jwt.rolesFrom(token));
        assertTrue(jwt.isAccessToken(token));
    }

    @Test
    void tamperedToken_failsValidation() {
        String token = jwt.generateAccessToken(1L, "x@y.com", List.of("CUSTOMER"));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(Exception.class, () -> jwt.parse(tampered));
    }

    @Test
    void wrongSecret_failsValidation() {
        JwtService other = new JwtService(
                "a-completely-different-secret-of-32+bytes!!!", 15);
        String token = jwt.generateAccessToken(1L, "x@y.com", List.of());
        assertThrows(SignatureException.class, () -> other.parse(token));
    }

    @Test
    void shortSecret_failsFastAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new JwtService("short", 15));
    }
}
