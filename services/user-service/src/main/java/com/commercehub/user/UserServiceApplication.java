package com.commercehub.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * User Service — identity provider for the whole platform.
 *
 * Issues JWT access tokens + opaque refresh tokens. Owns userdb
 * (PostgreSQL). Other services NEVER touch this DB — they validate JWTs
 * locally (stateless) or call /users/{id} through the gateway when they
 * need profile data.
 */
@SpringBootApplication
@EnableAsync
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
