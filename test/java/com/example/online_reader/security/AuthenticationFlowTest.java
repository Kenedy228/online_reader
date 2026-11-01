package com.example.online_reader.security;

import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "online-reader.auth.max-attempts=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
@Import(AuthRoutes.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    String address;
    String username;
    final String password = "synthetic-flow-password";
    String hash;

    @BeforeEach
    void seedIsolatedAccountAndAddress() {
        address = UUID.randomUUID().toString();
        username = "reader-" + UUID.randomUUID();
        hash = encoder.encode(password);
        users.saveAndFlush(new User(username, hash));
    }

    @Test
    void anonymousCannotUseProtectedRoutes() throws Exception {
        var response = mvc.perform(get("/auth-test/protected")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void validCredentialsCreateSessionWithErasedCredentialsAndUserAuthority() throws Exception {
        var result = login(username, password);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().isAuthenticated()).isTrue();
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(((org.springframework.security.core.userdetails.UserDetails)
                context.getAuthentication().getPrincipal()).getPassword()).isNull();
        assertThat(context.getAuthentication().getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_USER");
        mvc.perform(get("/auth-test/protected").session(session)).andExpect(content().string(username));
        mvc.perform(get("/login").session(session)).andExpect(redirectedUrl("/"));
        String html = mvc.perform(get("/").session(session)).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(username, "action=\"/logout\"", "name=\"_csrf\"").doesNotContain(password, hash);
        assertThat(users.findUserByUsername(username).getPasswordHash()).isEqualTo(hash);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "wrong-password", "disabled", "locked", "expired", "credentials-expired"})
    void allFailuresAreGenericAndKeepNoSensitiveSessionOrLogState(String reason, CapturedOutput output)
            throws Exception {
        var user = users.findUserByUsername(username);
        switch (reason) {
            case "disabled" -> user.setEnabled(false);
            case "locked" -> user.setAccountNonLocked(false);
            case "expired" -> user.setAccountNonExpired(false);
            case "credentials-expired" -> user.setCredentialsNonExpired(false);
        }
        users.saveAndFlush(user);
        var result = login(reason.equals("unknown") ? "absent-" + username : username,
                reason.equals("wrong-password") ? "synthetic-wrong-password" : password);
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(username, password, hash);
        var session = result.getRequest().getSession(false);
        assertThat(java.util.Collections.list(session.getAttributeNames()))
                .noneMatch(name -> name.contains("SECURITY_CONTEXT") || name.contains("EXCEPTION") || name.contains("USERNAME"));
        mvc.perform(get("/auth-test/protected").session((MockHttpSession) session))
                .andExpect(status().isFound());
        assertThat(output.getAll()).doesNotContain(password, hash, "synthetic-wrong-password");
        assertThat(users.findUserByUsername(username).getPasswordHash()).isEqualTo(hash);
    }

    @Test
    void csrfRunsBeforeBudgetAndForwardedHeadersCannotRotateIt() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/login").with(request -> {request.setRemoteAddr(address); return request;})
                    .param("username", username).param("password", password)).andExpect(status().isForbidden());
        }
        for (int i = 0; i < 3; i++) assertThat(login(username, password).getResponse().getStatus()).isEqualTo(302);
        var denied = login(username, password);
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(denied.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(denied.getRequest().getSession().getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-user", "long-user", "empty-password", "long-password", "utf8-overflow"})
    void invalidInputIsRejectedWithoutEcho(String kind) throws Exception {
        String submittedUser = switch (kind) {case "empty-user" -> " "; case "long-user" -> "u".repeat(51); default -> username;};
        String submittedPassword = switch (kind) {case "empty-password" -> ""; case "long-password" -> "p".repeat(73);
            case "utf8-overflow" -> "я".repeat(37); default -> password;};
        assertThat(login(submittedUser, submittedPassword).getResponse().getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void bcryptByteBoundaryAuthenticatesButCannotBeExtendedByTruncation() throws Exception {
        var user = users.findUserByUsername(username);
        String boundary = "я".repeat(36);
        user.setPasswordHash(encoder.encode(boundary));
        users.saveAndFlush(user);
        assertThat(login(username, boundary).getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(login(username, boundary + "x").getResponse().getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void logoutNeedsCsrfAndInvalidatesSession() throws Exception {
        var session = (MockHttpSession) login(username, password).getRequest().getSession();
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/logout").session(session));
        mvc.perform(get("/auth-test/protected").session(session)).andExpect(status().isOk());
        var page = mvc.perform(get("/").session(session)).andReturn();
        var token = (CsrfToken) page.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/logout").session(session).param(token.getParameterName(), token.getToken()))
                .andExpect(redirectedUrl("/login?logout")).andExpect(cookie().maxAge("JSESSIONID", 0));
        assertThat(session.isInvalid()).isTrue();
    }

    private MvcResult login(String user, String suppliedPassword) throws Exception {
        var page = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        var token = (CsrfToken) page.getRequest().getAttribute(CsrfToken.class.getName());
        return mvc.perform(post("/login").session((MockHttpSession) page.getRequest().getSession())
                .with(request -> { request.setRemoteAddr(address); return request; })
                .header("X-Forwarded-For", UUID.randomUUID().toString())
                .param(token.getParameterName(), token.getToken()).param("username", user)
                .param("password", suppliedPassword)).andReturn();
    }
}
