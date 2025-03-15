package com.example.online_reader.admin.model;

import com.example.online_reader.enums.RoleType;
import com.example.online_reader.actions.model.Action;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "admin")
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    //user credentials
    @Column(unique = true, nullable = false, length = 50)
    private String login;
    @Column(nullable = false, length = 60)
    private String password;
    @Column(nullable = false)
    private Boolean accountNonExpired = true;
    @Column(nullable = false)
    private Boolean accountNonLocked = true;
    @Column(nullable = false)
    private Boolean credentialsNonExpired = true;
    @Column(nullable = false)
    private Boolean enabled = true;

    @Transient
    private final RoleType role = RoleType.Admin;

    //authorities credentials
    @ManyToMany
    @JoinTable(
            name = "admin_actions",
            joinColumns = {
                    @JoinColumn(name = "admin_id")
            },
            inverseJoinColumns = {
                    @JoinColumn(name = "action_id")
            }
    )
    private final List<Action> actions = new ArrayList<>();

    public Admin() {

    }

    public Admin(String login, String password) {
        this.login = login;
        this.password = password;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public void setAction(Action action) {
        actions.add(action);
    }

    public List<Action> getActions() {
        return actions;
    }
}