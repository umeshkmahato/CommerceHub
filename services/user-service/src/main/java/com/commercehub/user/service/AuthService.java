package com.commercehub.user.service;

import com.commercehub.user.dto.AuthDtos;
import com.commercehub.user.entity.RefreshToken;
import com.commercehub.user.entity.Role;
import com.commercehub.user.entity.User;
import com.commercehub.user.repository.RefreshTokenRepository;
import com.commercehub.user.repository.RoleRepository;
import com.commercehub.user.repository.UserRepository;
import com.commercehub.user.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Authentication business logic — register, login, refresh rotation, logout.
 *
 * Token design:
 *  - Access JWT: stateless, verified by any service via shared secret.
 *  - Refresh token: OPAQUE random 256-bit string. We store only its SHA-256
 *    hash; the raw value exists only in the client's hands. Rotation on every
 *    use means a leaked refresh token has a short useful life.
 */
@Service
public class AuthService {

    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final RefreshTokenRepository tokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final long refreshTtlDays;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository userRepo, RoleRepository roleRepo,
                       RefreshTokenRepository tokenRepo, PasswordEncoder passwordEncoder,
                       AuthenticationManager authManager, JwtService jwtService,
                       @Value("${jwt.refresh-ttl-days:7}") long refreshTtlDays) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.tokenRepo = tokenRepo;
        this.passwordEncoder = passwordEncoder;
        this.authManager = authManager;
        this.jwtService = jwtService;
        this.refreshTtlDays = refreshTtlDays;
    }

    // ------------------------------ register ------------------------------

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest req) {
        if (userRepo.existsByEmailIgnoreCase(req.email())) {
            throw new IllegalArgumentException("Email already registered: " + req.email());
        }
        Role customer = roleRepo.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role missing — Flyway seed failed"));

        User user = new User();
        user.setEmail(req.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(req.password()));  // BCrypt
        user.setFirstName(req.firstName());
        user.setLastName(req.lastName());
        user.setPhone(req.phone());
        user.setRoles(Set.of(customer));
        userRepo.save(user);

        return issueTokens(user);
    }

    // ------------------------------ login ------------------------------

    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest req) {
        // AuthenticationManager runs DaoAuthenticationProvider:
        // loads UserDetails, BCrypt-matches the password, checks isActive.
        // Failure → BadCredentialsException (generic "bad credentials" —
        // never reveal whether the email or password was wrong).
        var auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        User user = userRepo.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new IllegalStateException("auth succeeded but user vanished"));
        return issueTokens(user);
    }

    // ------------------------------ refresh (rotation) ------------------------------

    @Transactional
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest req) {
        RefreshToken stored = tokenRepo.findByTokenHash(hash(req.refreshToken()))
                .orElseThrow(() -> new SecurityException("Invalid refresh token"));

        if (!stored.isUsable()) {
            // Token reuse after rotation/revocation is a theft signal —
            // revoke ALL of this user's tokens (documented security policy).
            if (stored.getRevokedAt() != null) {
                tokenRepo.revokeAllForUser(stored.getUser().getId(), LocalDateTime.now());
            }
            throw new SecurityException("Refresh token expired or revoked");
        }

        User user = stored.getUser();
        // Rotate: kill the old token BEFORE issuing the new pair.
        stored.revoke();
        tokenRepo.save(stored);
        return issueTokens(user);
    }

    @Transactional
    public void logout(AuthDtos.LogoutRequest req) {
        tokenRepo.findByTokenHash(hash(req.refreshToken()))
                .ifPresent(t -> { t.revoke(); tokenRepo.save(t); });
    }

    // ------------------------------ internals ------------------------------

    /** Create access JWT + persist hashed refresh token. */
    private AuthDtos.TokenResponse issueTokens(User user) {
        List<String> roles = user.getRoles().stream().map(Role::getName).toList();
        String access = jwtService.generateAccessToken(user.getId(), user.getEmail(), roles);

        String rawRefresh = generateRefreshToken();
        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hash(rawRefresh));
        entity.setExpiresAt(LocalDateTime.now().plus(refreshTtlDays, ChronoUnit.DAYS));
        tokenRepo.save(entity);

        return new AuthDtos.TokenResponse(access, rawRefresh, "Bearer",
                java.util.concurrent.TimeUnit.MINUTES.toSeconds(15));
    }

    /** 256-bit opaque token, URL-safe base64. */
    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex digest — what we store instead of the raw token. */
    private String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
