package com.example.online_reader.security;

import com.example.online_reader.user.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentAccount {
    private final UserRepository users;
    public CurrentAccount(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public Integer requireId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AccountPrincipal principal)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_USER"))) {
            throw new AccessDeniedException("Account unavailable");
        }
        // A session is not authority to adopt a reused username or keep using a disabled account.
        return users.findById(principal.getAccountId()).filter(user -> user.getRole() != null
                && user.getUsername().equals(principal.getUsername()) && Boolean.TRUE.equals(user.getEnabled())
                && Boolean.TRUE.equals(user.getAccountNonLocked()) && Boolean.TRUE.equals(user.getAccountNonExpired())
                && Boolean.TRUE.equals(user.getCredentialsNonExpired())).map(user -> user.getId())
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
    }
}
