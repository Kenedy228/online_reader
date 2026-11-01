package com.example.online_reader.user.service;

import com.example.online_reader.user.DTO.RegistrationForm;
import com.example.online_reader.user.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class RegistrationService {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public RegistrationService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public boolean register(@NotNull @Valid RegistrationForm form) {
        // Always hash before the single atomic insert, including duplicate submissions.
        // Neither confirmation nor privileges/account flags cross the repository boundary.
        return users.insertRegisteredUser(form.getUsername(), form.getEmail(), encoder.encode(form.getPassword())) == 1;
    }
}
