package com.example.online_reader.views.controller;

import com.example.online_reader.user.DTO.UserLoginDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.BindingResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
class LoginFormTest {
    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginRendersFreshFormWithoutBindingQueryCredentials(boolean query) throws Exception {
        var request = get("/login");
        String password = UUID.randomUUID().toString();
        if (query) {
            request.param("username", "query-reader").param("password", password);
        }
        var result = mvc.perform(request).andExpect(status().isOk()).andExpect(view().name("login"))
                .andExpect(model().attributeExists("user")).andReturn();
        var form = (UserLoginDTO) result.getModelAndView().getModel().get("user");
        assertThat(form.getUsername()).isNull();
        assertThat(form.getPassword()).isNull();
        assertThat(result.getResponse().getContentAsString()).contains("name=\"_csrf\"")
                .doesNotContain(password);
    }

    @Test
    void actualPostParametersBindAndRedirectToExistingHome() throws Exception {
        String password = UUID.randomUUID().toString();
        var result = submit("bound-reader", password);
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        mvc.perform(get(result.getResponse().getRedirectedUrl())).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource(value = {"'','present',username", "reader,'   ',password", "'','',both"})
    void invalidFieldsHaveOnlyTheirOwnErrorsAndPasswordIsCleared(String username, String password,
                                                               String invalid) throws Exception {
        String submittedPassword = "present".equals(password) ? UUID.randomUUID().toString() : password;
        var result = submit(username, submittedPassword);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var model = result.getModelAndView().getModel();
        var form = (UserLoginDTO) model.get("user");
        var errors = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "user");
        assertThat(form.getUsername()).isEqualTo(username);
        assertThat(form.getPassword()).isNull();
        assertThat(errors.hasFieldErrors("username")).isEqualTo(!"password".equals(invalid));
        assertThat(errors.hasFieldErrors("password")).isEqualTo(!"username".equals(invalid));
        if (errors.hasFieldErrors("password")) {
            assertThat(errors.getFieldError("password").getRejectedValue()).isNull();
        }
        if (!submittedPassword.isBlank()) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(submittedPassword);
        }
    }

    @Test
    void reflectedUsernameIsEscapedWhenPasswordIsInvalid() throws Exception {
        var result = submit("<script>alert(1)</script>", "");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("&lt;script&gt;")
                .doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void csrfProtectionStillRejectsPostWithoutToken() throws Exception {
        mvc.perform(post("/login").param("username", "reader").param("password", "synthetic-only"))
                .andExpect(status().isForbidden());
    }

    private MvcResult submit(String username, String password) throws Exception {
        var seed = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        var token = (CsrfToken) seed.getRequest().getAttribute(CsrfToken.class.getName());
        String value = token.getToken();
        var session = (MockHttpSession) seed.getRequest().getSession();
        return mvc.perform(post("/login").session(session).param(token.getParameterName(), value)
                .param("username", username).param("password", password)).andReturn();
    }
}
