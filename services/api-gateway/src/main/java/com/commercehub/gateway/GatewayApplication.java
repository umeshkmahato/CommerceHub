package com.commercehub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway — the ONLY public entry point.
 *
 * Responsibilities:
 *  - Route /api/v1/** to the right microservice (static config now,
 *    service discovery later).
 *  - Validate JWTs ONCE at the edge — downstream services trust the
 *    gateway-injected X-User-Id / X-User-Roles headers.
 *  - Strip client-supplied identity headers (anti-spoofing).
 *  - Rate limiting via Redis (distributed across gateway replicas).
 *  - CORS for the React frontend.
 *
 * This is where "security at the edge" lives: services stay simple and
 * fast because authn/authz already happened here.
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
