package com.example.online_reader.user.DTO;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RegistrationPolicyTest {
    @Test
    void acceptsExactPasswordBoundariesAndPreservesSpaces() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String password : new String[]{"x".repeat(15), "x".repeat(72), "я".repeat(36), "😀".repeat(18), " a long pass phrase "}) {
                var form = form(password);
                assertThat(factory.getValidator().validate(form)).isEmpty();
                assertThat(form.getPassword()).isEqualTo(password);
            }
        }
    }

    @Test
    void rejectsShortCodePointsAndOverflowBytesAndMissingConfirmation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String password : new String[]{"x".repeat(14), "x".repeat(73), "я".repeat(37), "😀".repeat(8)})
                assertThat(factory.getValidator().validate(form(password))).isNotEmpty();
            var missing = form("a sufficiently long phrase"); missing.setConfirmPassword(null);
            assertThat(factory.getValidator().validate(missing)).isNotEmpty();
        }
    }

    private RegistrationForm form(String password) {
        var form = new RegistrationForm();
        form.setUsername("reader_1"); form.setEmail("reader@example.test");
        form.setPassword(password); form.setConfirmPassword(password);
        return form;
    }
}
