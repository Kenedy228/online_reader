package com.example.online_reader.views.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
class LoginFormTest {
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"", "?error", "?logout"})
    void rendersBlankNativeFormWithoutReflectingCredentials(String query) throws Exception {
        String password = UUID.randomUUID().toString();
        var response = mvc.perform(get("/login" + query).param("username", "<script>unsafe</script>")
                        .param("password", password)).andExpect(status().isOk()).andExpect(view().name("login"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("name=\"_csrf\"")
                .doesNotContain(password, "<script>unsafe</script>");
    }

    @Test
    void csrfProtectionStillRejectsPostWithoutToken() throws Exception {
        mvc.perform(post("/login").param("username", "reader").param("password", "synthetic-only"))
                .andExpect(status().isForbidden());
    }
}
