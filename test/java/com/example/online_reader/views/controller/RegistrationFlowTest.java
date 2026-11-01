package com.example.online_reader.views.controller;

import com.example.online_reader.user.DTO.RegistrationForm;
import com.example.online_reader.user.repository.UserRepository;
import com.example.online_reader.user.service.RegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.validation.BindingResult;

import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "online-reader.registration.max-attempts=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
@ExtendWith(OutputCaptureExtension.class)
class RegistrationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RegistrationService registration;
    String address;
    String name;
    String email;
    final String password = "synthetic-registration-password";

    @BeforeEach
    void isolatedAddressAndAccount() {
        address = UUID.randomUUID().toString();
        name = "reg-" + UUID.randomUUID();
        email = "reg-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void formHasExplicitActionAndCsrfAndIgnoresQueryCredentials() throws Exception {
        var html = mvc.perform(get("/registration").param("username", "query-reader").param("email", email)
                .param("password", password).param("confirmPassword", password))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("action=\"/registration\"", "name=\"_csrf\"", "autocomplete=\"new-password\"")
                .doesNotContain(password, email, "query-reader");
    }

    @Test
    void validRegistrationCreatesHashOnlyAndAllowsRealLogin(CapturedOutput output) throws Exception {
        var values = values();
        values.put("email", "  " + email.toUpperCase(java.util.Locale.ROOT) + "  ");
        values.put("username", " " + name + " ");
        var result = submit(values);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?registered");
        var user = users.findUserByUsername(name);
        assertThat(user).isNotNull();
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getPasswordHash()).startsWith("{bcrypt}$2").doesNotContain(password);
        assertThat(encoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(result.getRequest().getSession().getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
        assertThat(java.util.Collections.list(result.getRequest().getSession().getAttributeNames()))
                .noneMatch(key -> key.contains("registration") || key.contains("password"));
        var login = mvc.perform(withCsrf(post("/login")).param("username", name).param("password", password))
                .andExpect(redirectedUrl("/")).andReturn();
        assertThat(login.getRequest().getSession().getAttribute("SPRING_SECURITY_CONTEXT")).isNotNull();
        String confirmation = mvc.perform(get("/login?registered")).andReturn().getResponse().getContentAsString();
        assertThat(confirmation).contains("Аккаунт создан").doesNotContain(password, email);
        assertThat(output.getAll()).doesNotContain(password, user.getPasswordHash(), email);
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate-name", "duplicate-email"})
    void collisionsNeverChangeExistingAccountAndUseSameGenericErrors(String kind, CapturedOutput output) throws Exception {
        submit(values());
        var original = users.findUserByUsername(name);
        var retry = values();
        if (kind.equals("duplicate-name")) retry.put("email", "other-" + email);
        else {retry.put("username", "other-" + UUID.randomUUID()); retry.put("email", email.toUpperCase(java.util.Locale.ROOT));}
        retry.put("password", "different-synthetic-password");
        retry.put("confirmPassword", "different-synthetic-password");
        var result = submit(retry);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var errors = (BindingResult) result.getModelAndView().getModel().get(BindingResult.MODEL_KEY_PREFIX + "registration");
        assertThat(errors.getFieldError("username").getDefaultMessage())
                .isEqualTo(errors.getFieldError("email").getDefaultMessage()).contains("Не удалось зарегистрировать");
        var after = users.findUserByUsername(name);
        assertThat(after.getEmail()).isEqualTo(original.getEmail());
        assertThat(after.getPasswordHash()).isEqualTo(original.getPasswordHash());
        if (kind.equals("duplicate-email")) assertThat(users.findUserByUsername(retry.get("username"))).isNull();
        assertSensitiveValuesCleared(result, "different-synthetic-password");
        assertThat(output.getAll()).doesNotContain(password, "different-synthetic-password", email, original.getPasswordHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {"short-name", "long-name", "invalid-name", "empty-name", "missing-email", "invalid-email",
            "long-email", "unicode-email", "short-password", "blank-password", "byte-overflow", "emoji-short",
            "mismatch", "long-confirmation"})
    void invalidInputDoesNotWriteAndClearsPasswords(String kind, CapturedOutput output) throws Exception {
        var values = values();
        switch (kind) {
            case "short-name" -> values.put("username", "ab");
            case "long-name" -> values.put("username", "x".repeat(51));
            case "invalid-name" -> values.put("username", "<script>registration-probe</script>");
            case "empty-name" -> values.put("username", " ");
            case "missing-email" -> values.remove("email");
            case "invalid-email" -> values.put("email", "not-an-email");
            case "long-email" -> values.put("email", "x".repeat(245) + "@example.test");
            case "unicode-email" -> values.put("email", "имя@example.test");
            case "short-password" -> values.put("password", "short");
            case "blank-password" -> values.put("password", " ".repeat(20));
            case "byte-overflow" -> values.put("password", "я".repeat(37));
            case "emoji-short" -> values.put("password", "😀".repeat(8));
            case "mismatch" -> values.put("confirmPassword", "another-synthetic-password");
            case "long-confirmation" -> values.put("confirmPassword", "q".repeat(73));
        }
        long before = users.count();
        var result = submit(values);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(users.count()).isEqualTo(before);
        assertSensitiveValuesCleared(result, values.get("password"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("<script>registration-probe</script>");
        assertThat(output.getAll()).doesNotContain(password);
    }

    @Test
    void privilegeFieldsAndValidationFlagsCannotBeBound() throws Exception {
        var values = values();
        values.putAll(Map.of("role", "ADMIN", "enabled", "false", "accountNonLocked", "false",
                "accountNonExpired", "false", "credentialsNonExpired", "false", "passwordHash", "{noop}owned"));
        assertThat(submit(values).getResponse().getRedirectedUrl()).isEqualTo("/login?registered");
        var user = users.findUserByUsername(name);
        assertThat(user.getRole().name()).isEqualTo("User");
        assertThat(user.getEnabled()).isTrue();
        assertThat(user.getAccountNonLocked()).isTrue();
        assertThat(user.getAccountNonExpired()).isTrue();
        assertThat(user.getCredentialsNonExpired()).isTrue();
        assertThat(encoder.matches(password, user.getPasswordHash())).isTrue();
        var invalid = values(); invalid.put("password", "short");
        invalid.put("passwordsMatch", "true"); invalid.put("passwordWithinByteLimit", "true");
        var rejected = submit(invalid);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        var errors = (BindingResult) rejected.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + "registration");
        assertThat(errors.getFieldError("password")).isNotNull();
    }

    @Test
    void authenticatedVisitorCannotCreateAnotherAccount() throws Exception {
        submit(values());
        var authenticated = mvc.perform(withCsrf(post("/login")).param("username", name).param("password", password))
                .andReturn();
        var session = (MockHttpSession) authenticated.getRequest().getSession();
        mvc.perform(get("/registration").session(session)).andExpect(redirectedUrl("/"));
        var tokenPage = mvc.perform(get("/").session(session)).andReturn();
        var token = (CsrfToken) tokenPage.getRequest().getAttribute(CsrfToken.class.getName());
        long before = users.count();
        mvc.perform(post("/registration").session(session).param(token.getParameterName(), token.getToken())
                .param("username", "ignored-user").param("email", "ignored@example.test")
                .param("password", password).param("confirmPassword", password)).andExpect(redirectedUrl("/"));
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void csrfRunsBeforeIndependentRateBudgetAndForwardedHeadersCannotBypass() throws Exception {
        for (int i = 0; i < 4; i++) mvc.perform(post("/registration").with(request -> {request.setRemoteAddr(address); return request;}))
                .andExpect(status().isForbidden());
        for (int i = 0; i < 3; i++) assertThat(submit(values()).getResponse().getStatus()).isIn(200, 302);
        long before = users.count();
        var denied = submit(values());
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(denied.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(users.count()).isEqualTo(before);
        // Registration exhaustion must not consume the separate login budget.
        mvc.perform(withCsrf(post("/login")).param("username", name).param("password", password))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void repeatedCredentialParametersCannotRegisterAJoinedPassword() throws Exception {
        var request = withCsrf(post("/registration"));
        values().forEach(request::param);
        request.param("password", "second-part").param("confirmPassword", "second-part");
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(users.findUserByUsername(name)).isNull();
        assertSensitiveValuesCleared(result, password);
    }

    @Test
    void serviceCannotSkipMandatoryEmailOrPasswordValidation() {
        var invalid = new RegistrationForm();
        invalid.setUsername(name); invalid.setPassword(password); invalid.setConfirmPassword(password);
        assertThatThrownBy(() -> registration.register(invalid)).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        assertThat(users.findUserByUsername(name)).isNull();
    }

    private Map<String, String> values() {
        return new HashMap<>(Map.of("username", name, "email", email, "password", password, "confirmPassword", password));
    }

    private MvcResult submit(Map<String, String> values) throws Exception {
        var request = withCsrf(post("/registration"));
        values.forEach(request::param);
        return mvc.perform(request).andReturn();
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        var seed = mvc.perform(get("/login")).andReturn();
        var token = (CsrfToken) seed.getRequest().getAttribute(CsrfToken.class.getName());
        return request.session((MockHttpSession) seed.getRequest().getSession())
                .with(raw -> {raw.setRemoteAddr(address); return raw;})
                .header("X-Forwarded-For", UUID.randomUUID().toString()).param(token.getParameterName(), token.getToken());
    }

    private void assertSensitiveValuesCleared(MvcResult result, String submittedPassword) throws Exception {
        var model = result.getModelAndView().getModel();
        var dto = (RegistrationForm) model.get("registration");
        assertThat(dto.getPassword()).isNull();
        assertThat(dto.getConfirmPassword()).isNull();
        var errors = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "registration");
        for (String field : new String[]{"password", "confirmPassword"}) {
            assertThat(errors.getRawFieldValue(field)).isNull();
            assertThat(errors.getFieldErrors(field)).allMatch(error -> error.getRejectedValue() == null);
        }
        String html = result.getResponse().getContentAsString();
        if (submittedPassword != null && !submittedPassword.isBlank() && submittedPassword.length() >= 8)
            assertThat(html).doesNotContain(submittedPassword);
        assertThat(html).doesNotContain(password, "another-synthetic-password", "q".repeat(73));
    }
}
