package com.example.online_reader.security;

import com.example.online_reader.config.PasswordConfiguration;
import com.example.online_reader.user.model.User;
import com.example.online_reader.admin.model.Admin;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PasswordStorageTest {
    @Test
    void nativeEncoderSaltsAndVerifiesOnlySupportedHashes() {
        var encoder = new PasswordConfiguration().passwordEncoder();
        String hash = encoder.encode("synthetic-fixture-password");
        assertThat(PasswordHashFormat.supports(hash)).isTrue();
        assertThat(hash).isNotEqualTo(encoder.encode("synthetic-fixture-password"));
        assertThat(encoder.matches("synthetic-fixture-password", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
        assertThatThrownBy(() -> encoder.matches("plain", "plain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.matches("plain", "{noop}plain")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PasswordHashFormat.supports(hash.replace("$12$", "$10$"))).isFalse();
    }

    @Test
    void entityValidationRejectsPlaintextForBothAccountTypes() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new User("reader", "plaintext")))
                    .anyMatch(error -> error.getPropertyPath().toString().equals("passwordHash"));
            assertThat(validator.validate(new Admin("admin", "plaintext")))
                    .anyMatch(error -> error.getPropertyPath().toString().equals("passwordHash"));
        }
    }
}
