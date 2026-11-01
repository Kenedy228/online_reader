package com.example.online_reader.views.controller;

import com.example.online_reader.user.DTO.UserLoginDTO;
import com.example.online_reader.views.service.IndexService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@ResourceLock(Resources.SYSTEM_OUT)
class ViewControllerTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginDoesNotExposeCredentials(boolean invalid, CapturedOutput output) {
        String username = "user-" + UUID.randomUUID();
        String marker = UUID.randomUUID().toString();
        var credentials = new UserLoginDTO(invalid ? "" : username, marker + "\nforged-log-line");
        var errors = new BeanPropertyBindingResult(credentials, "user");
        if (invalid) {
            errors.rejectValue("username", "NotBlank");
        }

        String view = new ViewController(new IndexService())
                .login(credentials, errors, new ExtendedModelMap());

        assertThat(output.getAll()).doesNotContain(marker);
        if (!invalid) {
            assertThat(output.getAll()).doesNotContain(username);
        }
        assertThat(view).isEqualTo(invalid ? "login" : "redirect:/index");
    }
}
