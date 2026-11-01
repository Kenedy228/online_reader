package com.example.online_reader.security;

import com.example.online_reader.enums.RoleType;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Objects;

/** Immutable account identity plus native User credential erasure; no email or entity in the session. */
public final class AccountPrincipal extends org.springframework.security.core.userdetails.User {
    private static final long serialVersionUID = 1L;
    private final Integer accountId;

    public AccountPrincipal(com.example.online_reader.user.model.User account) {
        super(account.getUsername(), account.getPasswordHash(), Boolean.TRUE.equals(account.getEnabled()),
                Boolean.TRUE.equals(account.getAccountNonExpired()), Boolean.TRUE.equals(account.getCredentialsNonExpired()),
                Boolean.TRUE.equals(account.getAccountNonLocked()), account.getRole() == RoleType.Admin
                        ? AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN")
                        : AuthorityUtils.createAuthorityList("ROLE_USER"));
        accountId = Objects.requireNonNull(account.getId());
    }

    public Integer getAccountId() { return accountId; }
}
