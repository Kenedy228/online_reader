package com.example.online_reader.security;

import com.example.online_reader.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public JpaUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        var user = users.findUserByUsername(username);
        if (user == null || user.getRole() == null || !PasswordHashFormat.supports(user.getPasswordHash())) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return new AccountPrincipal(user);
    }
}
