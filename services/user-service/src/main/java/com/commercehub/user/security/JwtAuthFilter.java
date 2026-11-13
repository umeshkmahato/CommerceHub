package com.commercehub.user.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT authentication filter — runs once per request BEFORE authorization.
 *
 * Flow: extract Bearer token → validate signature/expiry → load authorities
 * from token claims (NOT the DB — that's the whole point of stateless JWT)
 * → populate SecurityContext. Downstream code sees a normal authenticated
 * principal; nothing else knows JWT exists.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7);
            try {
                if (jwtService.isAccessToken(token)) {
                    Long userId = jwtService.userIdFrom(token);
                    List<SimpleGrantedAuthority> authorities = jwtService.rolesFrom(token).stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                            .toList();
                    var auth = new UsernamePasswordAuthenticationToken(
                            userId.toString(), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                // Invalid/expired token → leave context empty; SecurityConfig
                // returns 401 for protected endpoints. Don't write response here.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
