package com.commercehub.user;

import com.commercehub.user.entity.Role;
import com.commercehub.user.entity.User;
import com.commercehub.user.repository.RoleRepository;
import com.commercehub.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** User repository IT — real PostgreSQL 16: case-insensitive email lookup,
 *  Flyway-seeded roles, user↔role M:N join. */
@Testcontainers
@SpringBootTest
class UserRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("userdb");

    @Autowired UserRepository userRepo;
    @Autowired RoleRepository roleRepo;

    @Test
    void flywaySeedsRoles() {
        assertTrue(roleRepo.findByName("CUSTOMER").isPresent());
        assertTrue(roleRepo.findByName("ADMIN").isPresent());
    }

    @Test
    void findByEmailIgnoreCase_matchesAnyCase() {
        Role customer = roleRepo.findByName("CUSTOMER").orElseThrow();
        User u = new User();
        u.setEmail("Case.Test@Example.com");
        u.setPasswordHash("$2a$10$hash");
        u.setFirstName("Case"); u.setLastName("Test");
        u.setRoles(Set.of(customer));
        userRepo.saveAndFlush(u);

        var found = userRepo.findByEmailIgnoreCase("case.test@example.com");
        assertTrue(found.isPresent());
        assertEquals(u.getId(), found.get().getId());
        assertTrue(found.get().getRoles().stream()
                .anyMatch(r -> r.getName().equals("CUSTOMER")));
    }

    @Test
    void duplicateEmail_violatesCaseInsensitiveUniqueIndex() {
        Role customer = roleRepo.findByName("CUSTOMER").orElseThrow();
        User u1 = new User();
        u1.setEmail("dup@example.com"); u1.setPasswordHash("h");
        u1.setFirstName("A"); u1.setLastName("B"); u1.setRoles(Set.of(customer));
        userRepo.saveAndFlush(u1);

        User u2 = new User();
        u2.setEmail("DUP@example.com"); u2.setPasswordHash("h");   // different case
        u2.setFirstName("C"); u2.setLastName("D"); u2.setRoles(Set.of(customer));
        assertThrows(DataIntegrityViolationException.class,
                () -> userRepo.saveAndFlush(u2));
    }
}
