package com.commercehub.gateway.security;

import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Global auth filter — runs on EVERY request before routing.
 *
 * The trust contract downstream services rely on:
 *  1. Strip ANY client-supplied X-User-Id / X-User-Roles headers FIRST —
 *     otherwise a client could impersonate any user/admin.
 *  2. If path is public → forward as-is.
 *  3. Else require Bearer JWT → validate → inject X-User-Id (subject) and
 *     X-User-Roles (comma-separated) for the target service.
 *  4. Invalid/missing token on protected path → 401 JSON, never forwarded.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final GatewayJwtValidator validator;

    /** Paths reachable WITHOUT a token. */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/v1/auth/",       // login/register/refresh/logout
            "/api/v1/products",   // storefront browsing
            "/api/v1/categories",
            "/actuator/", "/swagger-ui", "/v3/api-docs"
    );

    public JwtAuthenticationFilter(GatewayJwtValidator validator) {
        this.validator = validator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        // Build mutated request: ALWAYS strip spoofable identity headers.
        var mutated = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove("X-User-Id");
                    h.remove("X-User-Roles");
                });

        if (isPublic(path)) {
            return chain.filter(exchange.mutate().request(mutated.build()).build());
        }

        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }

        try {
            Claims claims = validator.validate(auth.substring(7));
            String userId = claims.getSubject();
            String roles = String.join(",", validator.roles(claims));

            mutated.header("X-User-Id", userId)
                   .header("X-User-Roles", roles);

            return chain.filter(exchange.mutate().request(mutated.build()).build());
        } catch (Exception e) {
            return unauthorized(exchange, "Invalid or expired token");
        }
    }

    private boolean isPublic(String path) {
        // Admin paths are NEVER public even though they share prefixes.
        if (path.contains("/admin/")) return false;
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory()
                        .wrap(body.getBytes())));
    }

    @Override
    public int getOrder() { return -100; }   // run before routing filters
}
