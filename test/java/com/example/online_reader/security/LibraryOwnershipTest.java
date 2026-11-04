package com.example.online_reader.security;

import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.book.service.BookService;
import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LibraryOwnershipTest {
    private static final String HASH = new com.example.online_reader.config.PasswordConfiguration()
            .passwordEncoder().encode("synthetic-library-password");
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired BookRepository books;
    @Autowired BookService service;
    @Autowired JdbcTemplate jdbc;
    User first, second, admin;
    Book own, foreign;
    @BeforeEach void fixtures() {
        first = user(); second = user(); admin = user();
        jdbc.update("update users set role='Admin' where id=?", admin.getId());
        admin = users.findById(admin.getId()).orElseThrow();
        own = book(first, "Личная книга", Book.State.READY);
        foreign = book(second, "Чужая книга", Book.State.READY);
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    User user() { return users.saveAndFlush(new User("lib-" + UUID.randomUUID(), HASH)); }
    Book book(User owner, String title, Book.State state) {
        var b = Book.processing(owner, title, "Автор", Book.Format.TXT, 123,
                UUID.randomUUID().toString().replace("-", "").repeat(2), UUID.randomUUID());
        if (state == Book.State.READY) b.markReady();
        return books.saveAndFlush(b);
    }
    MockHttpSession session(User user) {
        var principal = new AccountPrincipal(user); principal.eraseCredentials();
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        return session;
    }
    String csrf(MockHttpSession session) throws Exception {
        var page = mvc.perform(get("/library").session(session)).andExpect(status().isOk()).andReturn();
        return ((CsrfToken)page.getRequest().getAttribute(CsrfToken.class.getName())).getToken();
    }
    String path(Book b) { return "/library/books/" + b.getId(); }

    @Test void listAndCardNeverExposeForeignBooksOrStorageReferences() throws Exception {
        var session = session(first);
        mvc.perform(get("/library").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Личная книга")))
                .andExpect(content().string(not(containsString("Чужая книга"))));
        mvc.perform(get(path(own)).session(session)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("sourceSha256"))))
                .andExpect(content().string(not(containsString("storageKey"))));
        for (User outsider : new User[]{second, admin}) {
            var other = session(outsider); String token = csrf(other);
            for (String suffix : new String[]{"", "/edit", "/delete"})
                mvc.perform(get(path(own) + suffix).session(other)).andExpect(status().isNotFound());
            mvc.perform(post(path(own)+"/edit").session(other).param("_csrf",token).param("title","")
                    .param("version","0")).andExpect(status().isNotFound());
            mvc.perform(post(path(own)+"/delete").session(other).param("_csrf",token).param("confirmed","true")
                    .param("version","0")).andExpect(status().isNotFound());
        }
        assertThat(books.findById(own.getId()).orElseThrow().getState()).isEqualTo(Book.State.READY);
        mvc.perform(get("/library/books/9223372036854775807").session(session)).andExpect(status().isNotFound());
    }
    @Test void adminCanUseOnlyOwnLibrary() throws Exception {
        var b = book(admin,"Книга администратора",Book.State.READY);
        mvc.perform(get("/library").session(session(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString(b.getTitle())))
                .andExpect(content().string(not(containsString(own.getTitle()))));
        mvc.perform(get(path(b)).session(session(admin))).andExpect(status().isOk());
    }
    @Test void metadataEditIsScopedEscapedAndRejectsStaleVersion() throws Exception {
        var session = session(first); String token = csrf(session);
        mvc.perform(get(path(own)+"/edit").session(session).param("title","query injection"))
                .andExpect(content().string(containsString("Личная книга")))
                .andExpect(content().string(not(containsString("query injection"))));
        mvc.perform(post(path(own)+"/edit").session(session).param("_csrf",token).param("title","<script>Название</script>")
                .param("author","Новое имя").param("version","0").param("owner.id",second.getId().toString())
                .param("state","DELETING").param("format","EPUB")).andExpect(redirectedUrl(path(own)));
        var saved = books.findByIdAndOwner_Id(own.getId(),first.getId()).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("<script>Название</script>");
        assertThat(saved.getFormat()).isEqualTo(Book.Format.TXT);
        assertThat(saved.getState()).isEqualTo(Book.State.READY);
        assertThat(saved.getVersion()).isEqualTo(1);
        mvc.perform(get(path(own)).session(session)).andExpect(content().string(containsString("&lt;script&gt;Название&lt;/script&gt;")));
        mvc.perform(post(path(own)+"/edit").session(session).param("_csrf",token).param("title","Мои несохранённые правки")
                .param("author","").param("version","0")).andExpect(status().isConflict())
                .andExpect(content().string(containsString("Мои несохранённые правки")))
                .andExpect(content().string(containsString("value=\"1\"")));
        assertThat(books.findById(own.getId()).orElseThrow().getTitle()).isEqualTo(saved.getTitle());
    }
    @Test void metadataValidationPreservesInputAndAcceptsUnicode() throws Exception {
        var session = session(first); String token = csrf(session);
        for(String title : new String[]{" ","a".repeat(201),"bad\u0000title"}) {
            mvc.perform(post(path(own)+"/edit").session(session).param("_csrf",token).param("title",title)
                    .param("author","").param("version","0")).andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("metadata","title"));
        }
        // H2 varchar counts UTF-16 units; the full 200-code-point persistence contract is tested on PostgreSQL.
        mvc.perform(post(path(own)+"/edit").session(session).param("_csrf",token).param("title","🙂".repeat(100))
                .param("author","").param("version","0")).andExpect(redirectedUrl(path(own)));
        assertThat(books.findById(own.getId()).orElseThrow().getTitle()).isEqualTo("🙂".repeat(100));
    }
    @Test void paginationHasBoundsAndStableTieBreaker() throws Exception {
        for(int i=0;i<24;i++) book(first,"Строка "+i,Book.State.READY);
        jdbc.update("update books set created_at = timestamp '2026-01-01 00:00:00' where owner_id=?", first.getId());
        var session = session(first);
        var result = mvc.perform(get("/library").session(session)).andExpect(status().isOk()).andReturn();
        var page = (org.springframework.data.domain.Page<?>)result.getModelAndView().getModel().get("books");
        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getTotalElements()).isEqualTo(25);
        @SuppressWarnings("unchecked") var content = (java.util.List<BookService.BookView>)page.getContent();
        assertThat(content.stream().map(BookService.BookView::id).toList()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        mvc.perform(get("/library").session(session).param("page","1")).andExpect(status().isOk());
        mvc.perform(get("/library").session(session).param("page","2")).andExpect(status().isNotFound());
        for(String invalid : new String[]{"0","51","-1","9999999999999","text"})
            mvc.perform(get("/library").session(session).param("size",invalid)).andExpect(status().isBadRequest());
        mvc.perform(get("/library").session(session).param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/library/books/0").session(session)).andExpect(status().isBadRequest());
    }
    @Test void deleteRequiresPostCsrfConfirmationAndVersionThenHidesImmediately() throws Exception {
        var session = session(first); String token = csrf(session);
        mvc.perform(get(path(own)+"/delete").session(session)).andExpect(status().isOk());
        assertThat(books.findById(own.getId()).orElseThrow().getState()).isEqualTo(Book.State.READY);
        mvc.perform(post(path(own)+"/delete").session(session).param("confirmed","true").param("version","0"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path(own)+"/delete").session(session).param("_csrf",token).param("version","0"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("deletion","confirmed"));
        mvc.perform(post(path(own)+"/delete").session(session).param("_csrf",token).param("version","99").param("confirmed","true"))
                .andExpect(status().isConflict());
        for(int i=0;i<2;i++) mvc.perform(post(path(own)+"/delete").session(session).param("_csrf",token)
                .param("version","0").param("confirmed","true")).andExpect(redirectedUrl("/library"));
        var deleted = books.findById(own.getId()).orElseThrow();
        assertThat(deleted.getState()).isEqualTo(Book.State.DELETING);
        assertThat(deleted.getOriginalSize()).isEqualTo(123);
        mvc.perform(get(path(own)).session(session)).andExpect(status().isNotFound());
        mvc.perform(post(path(own)+"/edit").session(session).param("_csrf",token).param("title","change")
                .param("version","1")).andExpect(status().isNotFound());
        mvc.perform(get("/library").session(session)).andExpect(content().string(containsString("Завершается удаление")))
                .andExpect(content().string(not(containsString("href=\""+path(own)+"\""))));
    }
    @Test void processingBookIsReadOnlyButCanBeCancelled() throws Exception {
        var b = book(first,"Обработка",Book.State.PROCESSING);
        var session = session(first); String token = csrf(session);
        mvc.perform(get(path(b)).session(session)).andExpect(status().isOk());
        mvc.perform(get(path(b)+"/edit").session(session)).andExpect(status().isConflict());
        mvc.perform(post(path(b)+"/delete").session(session).param("_csrf",token).param("confirmed","true")
                .param("version","0")).andExpect(redirectedUrl("/library"));
    }
    @Test void disabledOrRenamedOrRemovedAccountCannotUseExistingSession() throws Exception {
        var session = session(first);
        jdbc.update("update users set enabled=false where id=?",first.getId());
        mvc.perform(get("/library").session(session)).andExpect(status().isForbidden());
        jdbc.update("update users set enabled=true,username=? where id=?","renamed-"+UUID.randomUUID(),first.getId());
        mvc.perform(get(path(own)).session(session)).andExpect(status().isForbidden());
        var empty = user(); var removed = session(empty); users.deleteById(empty.getId());
        mvc.perform(get("/library").session(removed)).andExpect(status().isForbidden());
    }
    @Test void anonymousUnknownAndCreationRoutesStayClosed() throws Exception {
        mvc.perform(get("/library")).andExpect(status().isFound());
        mvc.perform(get("/library").header("Accept","application/json")).andExpect(status().isUnauthorized());
        var session=session(first);String token=csrf(session);
        mvc.perform(get("/library/unknown").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/library/books").session(session).param("_csrf",token)).andExpect(status().isForbidden());
        mvc.perform(post(path(own)+"/edit").session(session).param("title","CSRF bypass").param("version","0"))
                .andExpect(status().isForbidden());
    }
}
