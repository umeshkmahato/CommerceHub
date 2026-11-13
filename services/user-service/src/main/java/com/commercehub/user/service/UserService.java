package com.commercehub.user.service;

import com.commercehub.user.dto.AuthDtos;
import com.commercehub.user.entity.User;
import com.commercehub.user.entity.UserAddress;
import com.commercehub.user.repository.UserAddressRepository;
import com.commercehub.user.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Profile & address management for the authenticated user.
 *
 * Identity comes from the JWT principal (SecurityContext), never from a
 * client-supplied user id — this is the IDOR/BOLA prevention pattern:
 * you can only ever read/write YOUR OWN data.
 */
@Service
public class UserService {

    private final UserRepository userRepo;
    private final UserAddressRepository addressRepo;

    public UserService(UserRepository userRepo, UserAddressRepository addressRepo) {
        this.userRepo = userRepo;
        this.addressRepo = addressRepo;
    }

    // ------------------------------ profile ------------------------------

    @Transactional(readOnly = true)
    public AuthDtos.UserResponse me() {
        return toResponse(currentUser());
    }

    @Transactional
    public AuthDtos.UserResponse updateProfile(AuthDtos.UpdateProfileRequest req) {
        User user = currentUser();
        if (req.firstName() != null) user.setFirstName(req.firstName());
        if (req.lastName() != null) user.setLastName(req.lastName());
        if (req.phone() != null) user.setPhone(req.phone());
        return toResponse(userRepo.save(user));
    }

    // ------------------------------ addresses ------------------------------

    @Transactional(readOnly = true)
    public List<AuthDtos.AddressResponse> addresses() {
        Long userId = currentUserId();
        return addressRepo.findByUserIdOrderByIsDefaultDescCreatedAtAsc(userId).stream()
                .map(this::toAddressResponse).toList();
    }

    @Transactional
    public AuthDtos.AddressResponse addAddress(AuthDtos.AddressRequest req) {
        User user = currentUser();
        UserAddress addr = new UserAddress();
        addr.setUser(user);
        applyAddress(addr, req);
        // If this is the user's first address or marked default, ensure
        // exactly one default exists (DB EXCLUDE constraint is the backstop).
        if (Boolean.TRUE.equals(req.isDefault()) || addressRepo
                .findByUserIdOrderByIsDefaultDescCreatedAtAsc(user.getId()).isEmpty()) {
            addr.setDefault(true);
            clearOtherDefaults(user.getId(), null);
        }
        return toAddressResponse(addressRepo.save(addr));
    }

    @Transactional
    public AuthDtos.AddressResponse updateAddress(Long id, AuthDtos.AddressRequest req) {
        User user = currentUser();
        UserAddress addr = addressRepo.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Address not found"));
        applyAddress(addr, req);
        if (Boolean.TRUE.equals(req.isDefault())) {
            addr.setDefault(true);
            clearOtherDefaults(user.getId(), id);
        }
        return toAddressResponse(addressRepo.save(addr));
    }

    @Transactional
    public void deleteAddress(Long id) {
        addressRepo.deleteByIdAndUserId(id, currentUserId());
    }

    // ------------------------------ helpers ------------------------------

    /** Current user id from JWT principal — set by JwtAuthFilter. */
    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return Long.valueOf(principal.toString());
    }

    private User currentUser() {
        return userRepo.findById(currentUserId())
                .orElseThrow(() -> new IllegalStateException("JWT user no longer exists"));
    }

    private void clearOtherDefaults(Long userId, Long exceptId) {
        addressRepo.findByUserIdOrderByIsDefaultDescCreatedAtAsc(userId).stream()
                .filter(a -> !a.getId().equals(exceptId) && a.isDefault())
                .forEach(a -> { a.setDefault(false); addressRepo.save(a); });
    }

    private void applyAddress(UserAddress a, AuthDtos.AddressRequest req) {
        a.setLabel(req.label());
        a.setRecipient(req.recipient());
        a.setLine1(req.line1());
        a.setLine2(req.line2());
        a.setCity(req.city());
        a.setState(req.state());
        a.setPostalCode(req.postalCode());
        a.setCountry(req.country().toUpperCase());
        if (req.isDefault() != null) a.setDefault(req.isDefault());
    }

    private AuthDtos.UserResponse toResponse(User u) {
        return new AuthDtos.UserResponse(u.getId(), u.getEmail(), u.getFirstName(),
                u.getLastName(), u.getPhone(), u.getStatus().name(),
                u.getRoles().stream().map(r -> r.getName()).sorted().toList());
    }

    private AuthDtos.AddressResponse toAddressResponse(UserAddress a) {
        return new AuthDtos.AddressResponse(a.getId(), a.getLabel(), a.getRecipient(),
                a.getLine1(), a.getLine2(), a.getCity(), a.getState(),
                a.getPostalCode(), a.getCountry(), a.isDefault());
    }
}
