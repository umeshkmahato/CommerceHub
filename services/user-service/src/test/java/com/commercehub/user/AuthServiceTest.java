package com.commercehub.user;

import com.commercehub.user.dto.AuthDtos;
import com.commercehub.user.entity.RefreshToken;
import com.commercehub.user.entity.Role;
import com.commercehub.user.entity.User;
import com.commercehub.user.repository.RefreshTokenRepository;
import com.commercehub.user.repository.RoleRepository;
import com.commercehub.user.repository.UserRepository;
import com.commercehub.user.security.JwtService;
import com.commercehub.user.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService unit tests — the security-critical paths:
 * registration hashing, duplicate email rejection, refresh rotation,
 * and reuse-detection revocation.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepo;
    @Mock RoleRepository roleRepo;
    @Mock RefreshTokenRepository tokenRepo;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthenticationManager authManager;
    @Mock JwtService jwtService;

    AuthService authService;   // manual construction: constructor has a @Value long param

    private User user;
    private Role customer;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepo, roleRepo, tokenRepo,
                passwordEncoder, authManager, jwtService, 7L);

        customer = new Role();
        customer.setName("CUSTOMER");

        user = new User();
        user.setEmail("alice@example.com");
        user.setFirstName("Alice");
        user.setLastName("Smith");
        user.setRoles(Set.of(customer));
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 7L);
    }

    @Test
    void register_hashesPasswordAndAssignsCustomerRole() {
        AuthDtos.RegisterRequest req = new AuthDtos.RegisterRequest(
                "alice@example.com", "Passw0rd!", "Alice", "Smith", null);

        when(userRepo.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(roleRepo.findByName("CUSTOMER")).thenReturn(Optional.of(customer));
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("$2a$10$hashed");
        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("jwt-token");
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthDtos.TokenResponse resp = authService.register(req);

        assertEquals("jwt-token", resp.accessToken());
        assertNotNull(resp.refreshToken());
        verify(userRepo).save(argThat(u ->
                u.getPasswordHash().equals("$2a$10$hashed")   // never plaintext
                        && u.hasRole("CUSTOMER")));
    }

    @Test
    void register_duplicateEmail_rejected() {
        AuthDtos.RegisterRequest req = new AuthDtos.RegisterRequest(
                "dupe@x.com", "Passw0rd!", "A", "B", null);
        when(userRepo.existsByEmailIgnoreCase("dupe@x.com")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> authService.register(req));
        verify(userRepo, never()).save(any());
    }

    @Test
    void refresh_revokesOldTokenBeforeIssuingNew() {
        String rawRefresh = "raw-refresh-token";
        RefreshToken stored = new RefreshToken();
        stored.setUser(user);
        stored.setExpiresAt(java.time.LocalDateTime.now().plusDays(7));
        when(tokenRepo.findByTokenHash(anyString())).thenReturn(Optional.of(stored));
        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("new-jwt");
        when(tokenRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthDtos.TokenResponse resp = authService.refresh(
                new AuthDtos.RefreshRequest(rawRefresh));

        assertNotNull(stored.getRevokedAt());       // old token rotated out
        assertEquals("new-jwt", resp.accessToken());
        assertNotEquals(rawRefresh, resp.refreshToken());  // new opaque token
    }

    @Test
    void refresh_revokedToken_triggersReuseDetection() {
        RefreshToken stored = new RefreshToken();
        stored.setUser(user);
        stored.setExpiresAt(java.time.LocalDateTime.now().plusDays(7));
        stored.revoke();                            // already revoked = reuse!
        when(tokenRepo.findByTokenHash(anyString())).thenReturn(Optional.of(stored));

        assertThrows(SecurityException.class, () ->
                authService.refresh(new AuthDtos.RefreshRequest("stolen-token")));
        // Reuse detection: ALL user tokens revoked
        verify(tokenRepo).revokeAllForUser(eq(7L), any());
    }
}
