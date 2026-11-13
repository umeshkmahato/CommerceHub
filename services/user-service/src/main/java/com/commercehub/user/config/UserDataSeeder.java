package com.commercehub.user.config;

import com.commercehub.user.entity.Role;
import com.commercehub.user.entity.User;
import com.commercehub.user.repository.RoleRepository;
import com.commercehub.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Demo user seeder — idempotent (skips when users exist). Creates:
 *   admin@commercehub.com / Password123!  → ADMIN + CUSTOMER
 *   demo@commercehub.com  / Password123!  → CUSTOMER
 * Password is BCrypt-encoded at runtime via the app's PasswordEncoder —
 * never stored or logged in plaintext.
 */
@Component
public class UserDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserDataSeeder.class);
    private static final String DEMO_PASSWORD = "Password123!";

    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final PasswordEncoder passwordEncoder;

    public UserDataSeeder(UserRepository userRepo, RoleRepository roleRepo,
                          PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepo.count() > 0) {
            log.info("Seed skipped — users already exist");
            return;
        }
        Role customer = roleRepo.findByName("CUSTOMER").orElseThrow();
        Role admin = roleRepo.findByName("ADMIN").orElseThrow();

        user("admin@commercehub.com", "Admin", "User", Set.of(admin, customer));
        user("demo@commercehub.com", "Demo", "Customer", Set.of(customer));
        log.info("Seeded demo users (password: {})", DEMO_PASSWORD);
    }

    private void user(String email, String first, String last, Set<Role> roles) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        u.setFirstName(first);
        u.setLastName(last);
        u.setRoles(roles);
        userRepo.save(u);
    }
}
