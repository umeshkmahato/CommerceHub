package com.commercehub.user.security;

import com.commercehub.user.entity.User;
import com.commercehub.user.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridges our User entity to Spring Security's UserDetails.
 *
 * Roles are mapped to GrantedAuthority with the ROLE_ prefix convention
 * (hasRole("ADMIN") checks for authority "ROLE_ADMIN").
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepo;

    public CustomUserDetailsService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepo.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + email));
        if (!user.isActive()) {
            throw new UsernameNotFoundException("Account not active: " + email);
        }
        var authorities = user.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getName()))
                .toList();
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(), user.getPasswordHash(), authorities);
    }
}
