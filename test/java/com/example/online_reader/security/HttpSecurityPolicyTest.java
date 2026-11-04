package com.example.online_reader.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
class HttpSecurityPolicyTest {
    @Autowired MockMvc mvc;

    @Test
    void existingDefaultsAlreadyBlockSniffingFramingAndCaching() throws Exception {
        mvc.perform(get("/")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void htmlHasEnforcedCspAndNoReferrer() throws Exception {
        mvc.perform(get("/login")).andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void explicitJsonAnonymousRequestGetsUnauthorizedInsteadOfLoginRedirect() throws Exception {
        mvc.perform(get("/unregistered").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    void hstsOnlyTrustsSecureRequestAndCoversNoUnrelatedSubdomains() throws Exception {
        mvc.perform(get("/").header("X-Forwarded-Proto", "https"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mvc.perform(get("/").secure(true)).andExpect(header().string("Strict-Transport-Security", "max-age=31536000"));
    }
}
