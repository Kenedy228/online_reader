package com.example.online_reader.security;

import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
@Import(AuthorizationProbeRoutes.class)
class AuthorizationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    static final String PASSWORD = "synthetic-policy-password";

    @Test
    void unknownHandlerIsClosedEvenAfterLogin() throws Exception {
        mvc.perform(get("/unregistered/security-test").session(login(account(false)))).andExpect(status().isForbidden());
    }

    @Test
    void unlistedFilesNeverLeakToAnonymousTwoUsersOrAdmin() throws Exception {
        var first = login(account(false));
        var second = login(account(false));
        var admin = login(account(true));
        for (String path : new String[]{"/assets/private-book.txt", "/styles/private-book.css", "/js/private-book.js"}) {
            var anonymous = mvc.perform(get(path)).andExpect(status().isFound()).andReturn();
            assertThat(anonymous.getResponse().getContentAsString()).doesNotContain("synthetic-private");
            for (var session : new MockHttpSession[]{first, second, admin}) {
                var denied = mvc.perform(get(path).session(session)).andExpect(status().isForbidden()).andReturn();
                assertThat(denied.getResponse().getContentAsString()).doesNotContain("synthetic-private");
            }
        }
    }

    @Test
    void adminRouteUsesPersistedRoleAndUnknownResourcesStayClosed() throws Exception {
        mvc.perform(get("/admin/security-test")).andExpect(status().isFound());
        for (int i = 0; i < 2; i++) mvc.perform(get("/admin/security-test").session(login(account(false))))
                .andExpect(status().isForbidden());
        var admin = account(true);
        var session = login(admin);
        mvc.perform(get("/admin/security-test").session(session)).andExpect(status().isNoContent());
        var authentication = ((SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT")).getAuthentication();
        assertThat(authentication.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        var principal = (AccountPrincipal) authentication.getPrincipal();
        assertThat(principal.getAccountId()).isEqualTo(admin.getId());
        assertThat(principal.getPassword()).isNull();
        assertThat(authentication.getCredentials()).isNull();
        // These resources do not exist yet: this verifies fallback, not completed owner protection.
        for (String path : new String[]{"/unregistered/security-test", "/books/1", "/files/1", "/notes/1"})
            mvc.perform(get(path).session(session)).andExpect(status().isForbidden());
    }

    @Test
    void promotionRequiresFreshLogin() throws Exception {
        var user = account(false);
        var old = login(user);
        jdbc.update("update users set role='Admin' where id=?", user.getId());
        mvc.perform(get("/admin/security-test").session(old)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/security-test").session(login(user))).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"demoted", "disabled", "locked", "expired", "credentials-expired", "deleted", "renamed"})
    void revocationInvalidatesAdminAccessInExistingSession(String reason) throws Exception {
        var user = account(true);
        var session = login(user);
        mvc.perform(get("/admin/security-test").session(session)).andExpect(status().isNoContent());
        String sql = switch (reason) {
            case "demoted" -> "update users set role='User' where id=?";
            case "disabled" -> "update users set enabled=false where id=?";
            case "locked" -> "update users set account_non_locked=false where id=?";
            case "expired" -> "update users set account_non_expired=false where id=?";
            case "credentials-expired" -> "update users set credentials_non_expired=false where id=?";
            case "deleted" -> "delete from users where id=?";
            default -> "update users set username='renamed-' || id where id=?";
        };
        jdbc.update(sql, user.getId());
        mvc.perform(get("/admin/security-test").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void reusedUsernameCannotAdoptDeletedAccountSession() throws Exception {
        var original = account(true);
        var session = login(original);
        jdbc.update("delete from users where id=?", original.getId());
        var replacement = users.saveAndFlush(new User(original.getUsername(), encoder.encode(PASSWORD)));
        assertThat(replacement.getId()).isNotEqualTo(original.getId());
        jdbc.update("update users set role='Admin' where id=?", replacement.getId());
        mvc.perform(get("/admin/security-test").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/security-test").session(login(replacement))).andExpect(status().isNoContent());
    }

    @Test
    void legacyAdminRowsNeitherAuthenticateNorElevateUsersWithSameName() throws Exception {
        String name = "legacy-" + UUID.randomUUID();
        jdbc.update("insert into admin(login,password,enabled,account_non_locked,account_non_expired,credentials_non_expired)"
                + " values(?,?,true,true,true,true)", name, encoder.encode(PASSWORD));
        loginRequest(name).andExpect(redirectedUrl("/login?error"));
        var user = users.saveAndFlush(new User(name, encoder.encode(PASSWORD)));
        mvc.perform(get("/admin/security-test").session(login(user))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assets/logo.svg", "/styles/login.css", "/js/passwordVisibilityChangerForRegistration.bundle.js"})
    void ownedAssetsRemainAvailableOnGetAndHead(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
        mvc.perform(head(path)).andExpect(status().isOk());
    }

    private User account(boolean admin) {
        var user = users.saveAndFlush(new User("policy-" + UUID.randomUUID(), encoder.encode(PASSWORD)));
        if (admin) jdbc.update("update users set role='Admin' where id=?", user.getId());
        return user;
    }

    private MockHttpSession login(User user) throws Exception {
        return (MockHttpSession) loginRequest(user.getUsername()).andExpect(redirectedUrl("/"))
                .andReturn().getRequest().getSession();
    }

    private org.springframework.test.web.servlet.ResultActions loginRequest(String name) throws Exception {
        var page = mvc.perform(get("/login")).andReturn();
        var token = (CsrfToken) page.getRequest().getAttribute(CsrfToken.class.getName());
        return mvc.perform(post("/login").session((MockHttpSession) page.getRequest().getSession())
                .with(request -> {request.setRemoteAddr(UUID.randomUUID().toString()); return request;})
                .param(token.getParameterName(), token.getToken()).param("username", name).param("password", PASSWORD));
    }
}
