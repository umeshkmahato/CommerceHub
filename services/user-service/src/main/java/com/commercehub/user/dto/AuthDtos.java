package com.commercehub.user.dto;

import jakarta.validation.constraints.*;

/**
 * Auth DTOs. Password rules live here so every entry point validates
 * identically: min 8 chars, letter + digit required.
 */
public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email(message = "Valid email required") @Size(max = 255)
            String email,

            @NotBlank @Size(min = 8, max = 100, message = "Password must be 8-100 chars")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                     message = "Password must contain letters and digits")
            String password,

            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 30) String phone
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    public record LogoutRequest(
            @NotBlank String refreshToken
    ) {}

    /** Tokens returned to the client. Access is short-lived; refresh rotates. */
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,        // always "Bearer"
            long expiresInSeconds
    ) {}

    public record UserResponse(
            Long id,
            String email,
            String firstName,
            String lastName,
            String phone,
            String status,
            java.util.List<String> roles
    ) {}

    public record UpdateProfileRequest(
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 30) String phone
    ) {}

    public record AddressRequest(
            @NotBlank @Size(max = 40) String label,
            @NotBlank @Size(max = 120) String recipient,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String state,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(min = 2, max = 2, message = "ISO country code, e.g. US") String country,
            Boolean isDefault
    ) {}

    public record AddressResponse(
            Long id, String label, String recipient, String line1, String line2,
            String city, String state, String postalCode, String country, boolean isDefault
    ) {}
}
