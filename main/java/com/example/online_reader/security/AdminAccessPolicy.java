package com.example.online_reader.security;

import com.example.online_reader.enums.RoleType;
import com.example.online_reader.user.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAccessPolicy {
    private final UserRepository users;

    public AdminAccessPolicy(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public boolean isAllowed(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)
                || authentication.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            return false;
        }
        // Snapshot authorities alone survive demotion. Recheck the current account by immutable ID,
        // never by a submitted ID or a possibly reused username; promotion requires a fresh login.
        return users.findById(principal.getAccountId()).filter(account ->
                account.getUsername().equals(principal.getUsername()) && account.getRole() == RoleType.Admin
                        && Boolean.TRUE.equals(account.getEnabled()) && Boolean.TRUE.equals(account.getAccountNonLocked())
                        && Boolean.TRUE.equals(account.getAccountNonExpired())
                        && Boolean.TRUE.equals(account.getCredentialsNonExpired())).isPresent();
    }
}
