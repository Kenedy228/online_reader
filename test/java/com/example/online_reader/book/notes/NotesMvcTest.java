package com.example.online_reader.book.notes;

import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.book.files.PrivateBookStorage;
import com.example.online_reader.book.progress.*;
import com.example.online_reader.book.service.BookService;
import com.example.online_reader.security.AccountPrincipal;
import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class NotesMvcTest {
    static final String HASH=new com.example.online_reader.config.PasswordConfiguration().passwordEncoder().encode("Synthetic-note-password");
    @Autowired MockMvc mvc;@Autowired UserRepository users;@Autowired BookRepository books;@Autowired NoteRepository notes;@Autowired NoteService service;
    @Autowired PrivateBookStorage storage;@Autowired JdbcTemplate jdbc;@Autowired ProgressService progress;@Autowired BookService library;
    User owner;Book book;MockHttpSession session;String csrf;
    @BeforeEach void fixtures()throws Exception {
        owner=user();book=book(owner);session=session(owner);
        var page=mvc.perform(get("/library").session(session)).andReturn();csrf=((CsrfToken)page.getRequest().getAttribute(CsrfToken.class.getName())).getToken();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    User user(){return users.saveAndFlush(new User("notes-"+UUID.randomUUID(),HASH));}
    Book book(User user)throws Exception {
        byte[] text="Синтетический текст 🙂\nещё текст".getBytes(java.nio.charset.StandardCharsets.UTF_8);var key=UUID.randomUUID();
        var b=Book.processing(user,"Книга <script>","Автор",Book.Format.TXT,text.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text)),key);b.markReady();b=books.saveAndFlush(b);
        var root=(Path)org.springframework.test.util.ReflectionTestUtils.getField(storage,"root");Files.write(root.resolve("sources").resolve(key+".book"),text);return b;
    }
    MockHttpSession session(User user){var principal=new AccountPrincipal(user);principal.eraseCredentials();var auth=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());var result=new MockHttpSession();result.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new SecurityContextImpl(auth));return result;}
    void authenticate(){var principal=new AccountPrincipal(owner);principal.eraseCredentials();SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));}
    String path(){return "/library/books/"+book.getId()+"/notes";}
    NoteForm form()throws Exception {return (NoteForm)mvc.perform(get(path()+"/new").session(session)).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("note");}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(NoteForm form,String text){return post(path()).session(session).param("_csrf",csrf).param("text",text).param("chapter",form.getChapter()).param("offset",form.getOffset().toString()).param("fingerprint",form.getFingerprint()).param("version",form.getVersion().toString()).param("proof",form.getProof());}
    Long create(String text)throws Exception {var response=mvc.perform(create(form(),text)).andExpect(status().isSeeOther()).andReturn().getResponse();String location=response.getRedirectedUrl();return Long.valueOf(location.substring(location.lastIndexOf('/')+1));}
    @Test void browserHeadersAreNotFormFieldsAndCannotSupplyNoteText()throws Exception {
        var form=form();
        mvc.perform(create(form,"Заметка из браузера").header("User-Agent","synthetic-browser").header("Content-Type","application/x-www-form-urlencoded").header("Sec-Fetch-Mode","navigate"))
                .andExpect(status().isSeeOther());
        mvc.perform(post(path()).session(session).param("_csrf",csrf).param("chapter",form.getChapter()).param("offset",form.getOffset().toString())
                .param("fingerprint",form.getFingerprint()).param("version",form.getVersion().toString()).param("proof",form.getProof()).header("text","Header must not supply content"))
                .andExpect(status().isOk());
        assertThat(notes.countByBookIdAndOwnerId(book.getId(),owner.getId())).isEqualTo(1);
    }
    @Test void nativeCrudPersistsPlaintextAndRejectsStaleEditDeleteWithoutSilentOverwrite()throws Exception {
        String payload="</textarea><script>window.PWN=1</script>🙂\r\nстрока";Long id=create(payload);
        assertThat(notes.findById(id).orElseThrow().getText()).isEqualTo(payload.replace("\r\n","\n"));
        mvc.perform(get(path()+"/"+id).session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;window.PWN=1&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>window.PWN"))));
        mvc.perform(post(path()+"/"+id+"/edit").session(session).param("_csrf",csrf).param("text","Первая правка").param("version","0")).andExpect(status().isSeeOther());
        mvc.perform(post(path()+"/"+id+"/edit").session(session).param("_csrf",csrf).param("text","Мой черновик").param("version","0")).andExpect(status().isConflict())
                .andExpect(content().string(containsString("Первая правка"))).andExpect(content().string(containsString("Мой черновик")));
        assertThat(notes.findById(id).orElseThrow().getText()).isEqualTo("Первая правка");
        mvc.perform(post(path()+"/"+id+"/delete").session(session).param("_csrf",csrf).param("version","0").param("confirmed","true")).andExpect(status().isConflict()).andExpect(content().string(not(containsString("checked=\"checked\""))));
        mvc.perform(post(path()+"/"+id+"/delete").session(session).param("_csrf",csrf).param("version","1")).andExpect(status().isOk());assertThat(notes.existsById(id)).isTrue();
        mvc.perform(post(path()+"/"+id+"/delete").session(session).param("_csrf",csrf).param("version","1").param("confirmed","true")).andExpect(status().isSeeOther());
        assertThat(notes.findById(id)).isEmpty();mvc.perform(get(path()+"/"+id).session(session)).andExpect(status().isNotFound());
    }
    @Test void validationKeepsNormalDraftsBoundsHostileEchoAndRefreshesOldProofOnlyAfterExplicitRetry()throws Exception {
        var form=form();String draft="x".repeat(3000);
        mvc.perform(create(form,draft)).andExpect(status().isOk()).andExpect(content().string(containsString(draft)));assertThat(notes.countByBookIdAndOwnerId(book.getId(),owner.getId())).isZero();
        var bounded=mvc.perform(create(form,"x".repeat(20000))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(bounded).contains("показана только его часть").doesNotContain("x".repeat(9000));
        mvc.perform(create(form," ")).andExpect(status().isOk());
        var original=form.getProof();form.setProof("forged");
        var result=mvc.perform(create(form,"Сохранённый черновик")).andExpect(status().isConflict()).andExpect(content().string(containsString("Сохранённый черновик"))).andReturn();
        var refreshed=(NoteForm)result.getModelAndView().getModel().get("note");assertThat(refreshed.getProof()).isEqualTo(original);
        assertThat(notes.countByBookIdAndOwnerId(book.getId(),owner.getId())).isZero();mvc.perform(create(refreshed,"Сохранённый черновик")).andExpect(status().isSeeOther());
        mvc.perform(create(form,"Хороший текст").param("owner_id","7")).andExpect(status().isBadRequest());
        form.setOffset(-1);mvc.perform(create(form,"Хороший текст")).andExpect(status().isBadRequest());
    }
    @Test void nativeTextareaCrLfIsNormalizedBeforeLengthValidationOnCreateAndEdit()throws Exception {
        Long id=create("x\r\n".repeat(1000));assertThat(notes.findById(id).orElseThrow().getText()).isEqualTo("x\n".repeat(1000).strip());
        mvc.perform(post(path()+"/"+id+"/edit").session(session).param("_csrf",csrf).param("text","y\r\n".repeat(1000)).param("version","0")).andExpect(status().isSeeOther());
        assertThat(notes.findById(id).orElseThrow().getText()).isEqualTo("y\n".repeat(1000).strip());
    }
    @Test void unicodeLimitIsCodePointsAndEditCannotRetargetOrAssignOwner()throws Exception {
        Long id=create("🙂".repeat(2000));assertThat(notes.findById(id).orElseThrow().getText()).hasSize(4000);
        var position=notes.findById(id).orElseThrow().position();
        mvc.perform(post(path()+"/"+id+"/edit").session(session).param("_csrf",csrf).param("text","Новый текст").param("version","0").param("offset","20")).andExpect(status().isBadRequest());
        mvc.perform(post(path()+"/"+id+"/edit").session(session).param("_csrf",csrf).param("text","Новый текст").param("version","0")).andExpect(status().isSeeOther());assertThat(notes.findById(id).orElseThrow().position()).isEqualTo(position);
        mvc.perform(create(form(),"🙂".repeat(2001))).andExpect(status().isOk());assertThat(notes.countByBookIdAndOwnerId(book.getId(),owner.getId())).isEqualTo(1);
    }
    @Test void ownerAdminNestedBookIdsLiveFlagsAndCsrfRemainPrivate()throws Exception {
        Long id=create("Личная заметка");
        for(boolean admin:List.of(false,true)) {
            var other=user();if(admin){jdbc.update("update users set role='Admin' where id=?",other.getId());other=users.findById(other.getId()).orElseThrow();}
            var outsider=session(other);var ownBook=book(other);String otherPath="/library/books/"+ownBook.getId()+"/notes/"+id;
            for(String suffix:List.of("","/edit","/delete")) {
                mvc.perform(get(path()+"/"+id+suffix).session(outsider)).andExpect(status().isNotFound());
                mvc.perform(get(otherPath+suffix).session(outsider)).andExpect(status().isNotFound());
            }
            var token=(CsrfToken)mvc.perform(get("/library").session(outsider)).andReturn().getRequest().getAttribute(CsrfToken.class.getName());
            for(String suffix:List.of("/edit","/delete"))mvc.perform(post(otherPath+suffix).session(outsider).param("_csrf",token.getToken()).param("version","0").param("text","Чужая правка").param("confirmed","true")).andExpect(status().isNotFound());
            mvc.perform(get(path()).session(outsider)).andExpect(status().isNotFound());
        }
        mvc.perform(get(path())).andExpect(status().isFound());mvc.perform(post(path()+"/"+id+"/delete").session(session).param("confirmed","true").param("version","0")).andExpect(status().isForbidden());
        jdbc.update("update users set enabled=false where id=?",owner.getId());mvc.perform(get(path()).session(session)).andExpect(status().isForbidden());
    }
    @Test void paginationIsBoundedAndStaleLocatorKeepsTextWithoutAReadingLink()throws Exception {
        for(int i=0;i<21;i++)notes.saveAndFlush(BookNote.at(book.getId(),owner.getId(),"Заметка "+i,new ReadingPosition("text",0,book.getSourceSha256(),1)));
        var first=mvc.perform(get(path()).session(session)).andExpect(status().isOk()).andReturn();var page=(org.springframework.data.domain.Page<?>)first.getModelAndView().getModel().get("notes");assertThat(page.getNumberOfElements()).isEqualTo(20);
        mvc.perform(get(path()).session(session).param("page","1")).andExpect(status().isOk());mvc.perform(get(path()).session(session).param("size","51")).andExpect(status().isBadRequest());
        var note=notes.findByBookIdAndOwnerId(book.getId(),owner.getId(),org.springframework.data.domain.PageRequest.of(0,1)).getContent().getFirst();jdbc.update("update book_notes set representation_version=2 where id=?",note.getId());
        mvc.perform(get(path()+"/"+note.getId()).session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("Позиция устарела"))).andExpect(content().string(not(containsString("Открыть место в книге"))));
    }
    @Test void logicalBookDeleteRemovesNotesAndProgressAtomicallyAndStopsNewWrites()throws Exception {
        Long id=create("Сохранённая заметка");var form=form();authenticate();progress.save(book.getId(),form.position(),0,form.getProof());library.delete(book.getId(),0);
        assertThat(notes.findById(id)).isEmpty();assertThat(jdbc.queryForObject("select count(*) from reading_progress where book_id=?",Integer.class,book.getId())).isZero();
        mvc.perform(get(path()).session(session)).andExpect(status().isNotFound());mvc.perform(create(form,"После удаления")).andExpect(status().isNotFound());
    }
}
