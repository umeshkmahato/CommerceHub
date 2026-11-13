package com.commercehub.user.controller;

import com.commercehub.user.dto.AuthDtos;
import com.commercehub.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * /api/v1/users — authenticated self-service endpoints.
 * All require a valid JWT (anyRequest().authenticated() in SecurityConfig).
 * Identity always comes from the token — never from path/body params.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Profile and addresses for the logged-in user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Current user's profile")
    public AuthDtos.UserResponse me() {
        return userService.me();
    }

    @PutMapping("/me")
    @Operation(summary = "Update profile (name, phone)")
    public AuthDtos.UserResponse updateMe(@Valid @RequestBody AuthDtos.UpdateProfileRequest req) {
        return userService.updateProfile(req);
    }

    @GetMapping("/me/addresses")
    @Operation(summary = "List my addresses")
    public List<AuthDtos.AddressResponse> addresses() {
        return userService.addresses();
    }

    @PostMapping("/me/addresses")
    @Operation(summary = "Add an address")
    public ResponseEntity<AuthDtos.AddressResponse> addAddress(
            @Valid @RequestBody AuthDtos.AddressRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.addAddress(req));
    }

    @PutMapping("/me/addresses/{id}")
    @Operation(summary = "Update an address (must be mine)")
    public AuthDtos.AddressResponse updateAddress(
            @PathVariable Long id, @Valid @RequestBody AuthDtos.AddressRequest req) {
        return userService.updateAddress(id, req);
    }

    @DeleteMapping("/me/addresses/{id}")
    @Operation(summary = "Delete an address")
    public ResponseEntity<Void> deleteAddress(@PathVariable Long id) {
        userService.deleteAddress(id);
        return ResponseEntity.noContent().build();
    }

    /** Example of method-level authz — admin-only endpoint pattern. */
    @GetMapping("/admin/ping")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin-only smoke endpoint")
    public String adminPing() { return "admin-ok"; }
}
