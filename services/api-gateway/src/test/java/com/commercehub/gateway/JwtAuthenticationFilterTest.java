package com.commercehub.gateway;

import com.commercehub.gateway.security.GatewayJwtValidator;
import com.commercehub.gateway.security.JwtAuthenticationFilter;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Gateway auth filter tests — the edge security contract:
 * public paths pass, protected paths require JWT, spoofed headers stripped,
 * valid tokens inject X-User-Id/X-User-Roles.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock GatewayJwtValidator validator;
    @Mock GatewayFilterChain chain;

    JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(validator);
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchange(String method, String path, String auth) {
        var req = MockServerHttpRequest.method(method, path);
        if (auth != null) req.header(HttpHeaders.AUTHORIZATION, auth);
        return MockServerWebExchange.from(req);
    }

    @Test
    void publicPath_passesWithoutToken() {
        var ex = exchange("POST", "/api/v1/auth/login", null);
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        verify(chain).filter(any());
        verifyNoInteractions(validator);
    }

    @Test
    void protectedPath_noToken_returns401() {
        var ex = exchange("GET", "/api/v1/orders", null);
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assert ex.getResponse().getStatusCode().value() == 401;
        verify(chain, never()).filter(any());
    }

    @Test
    void protectedPath_validToken_injectsIdentityHeaders() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("42");
        when(validator.validate("good-token")).thenReturn(claims);
        when(validator.roles(claims)).thenReturn(List.of("CUSTOMER"));

        var ex = exchange("GET", "/api/v1/orders", "Bearer good-token");
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        verify(chain).filter(argThat(swe -> {
            var h = swe.getRequest().getHeaders();
            return "42".equals(h.getFirst("X-User-Id"))
                    && "CUSTOMER".equals(h.getFirst("X-User-Roles"));
        }));
    }

    @Test
    void spoofedHeaders_areStrippedEvenOnPublicPath() {
        var req = MockServerHttpRequest.post("/api/v1/auth/login")
                .header("X-User-Id", "999").header("X-User-Roles", "ADMIN");
        var ex = MockServerWebExchange.from(req);
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        verify(chain).filter(argThat(swe ->
                swe.getRequest().getHeaders().getFirst("X-User-Id") == null
                        && swe.getRequest().getHeaders().getFirst("X-User-Roles") == null));
    }

    @Test
    void invalidToken_returns401() {
        when(validator.validate(anyString())).thenThrow(new RuntimeException("bad sig"));
        var ex = exchange("GET", "/api/v1/cart", "Bearer forged");
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assert ex.getResponse().getStatusCode().value() == 401;
    }

    @Test
    void adminPath_requiresTokenEvenThoughProductPrefixIsPublic() {
        // /api/v1/admin/products must NOT be treated as public browse path.
        var ex = exchange("POST", "/api/v1/admin/products", null);
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assert ex.getResponse().getStatusCode().value() == 401;
    }
}
