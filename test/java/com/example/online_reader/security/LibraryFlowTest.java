package com.example.online_reader.security;

import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
class LibraryFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @Test
    void authenticatedUserCanOpenOwnEmptyLibrary() throws Exception {
        String name="library-"+UUID.randomUUID();
        users.saveAndFlush(new User(name,encoder.encode("synthetic-library-password")));
        var page=mvc.perform(get("/login")).andReturn();
        var csrf=(CsrfToken)page.getRequest().getAttribute(CsrfToken.class.getName());
        var login=mvc.perform(post("/login").session((MockHttpSession)page.getRequest().getSession())
                .param(csrf.getParameterName(),csrf.getToken()).param("username",name)
                .param("password","synthetic-library-password")).andExpect(redirectedUrl("/")).andReturn();
        mvc.perform(get("/library").session((MockHttpSession)login.getRequest().getSession()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("В библиотеке пока нет книг")));
    }
}
