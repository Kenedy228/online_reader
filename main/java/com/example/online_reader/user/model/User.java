package com.example.online_reader.user.model;

import com.example.online_reader.enums.RoleType;
import com.example.online_reader.actions.model.Action;
import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import com.example.online_reader.security.PasswordHashFormat;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Nullable only for legacy accounts that predate email registration; no invented backfill.
    @Column(unique = true, length = 254)
    private String email;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    //user credentials
    @Column(unique = true, nullable = false, length = 50)
    private String username;
    @Column(name = "password", nullable = false, length = 255)
    @Pattern(regexp = PasswordHashFormat.BCRYPT, message = "Password must be an encoded hash")
    private String passwordHash;
    @Column(nullable = false)
    private Boolean accountNonExpired = true;
    @Column(nullable = false)
    private Boolean accountNonLocked = true;
    @Column(nullable = false)
    private Boolean credentialsNonExpired = true;
    @Column(nullable = false)
    private Boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoleType role = RoleType.User;

    public Integer getId() { return id; }

    public User() {

    }

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Boolean getAccountNonExpired() {
        return accountNonExpired;
    }

    public void setAccountNonExpired(Boolean accountNonExpired) {
        this.accountNonExpired = accountNonExpired;
    }

    public Boolean getAccountNonLocked() {
        return accountNonLocked;
    }

    public void setAccountNonLocked(Boolean accountNonLocked) {
        this.accountNonLocked = accountNonLocked;
    }

    public Boolean getCredentialsNonExpired() {
        return credentialsNonExpired;
    }

    public void setCredentialsNonExpired(Boolean credentialsNonExpired) {
        this.credentialsNonExpired = credentialsNonExpired;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public RoleType getRole() {
        return role;
    }
}
