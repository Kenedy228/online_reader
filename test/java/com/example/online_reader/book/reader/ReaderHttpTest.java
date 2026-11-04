package com.example.online_reader.book.reader;

import com.example.online_reader.book.files.*;
import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import com.example.online_reader.security.AccountPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReaderHttpTest {
    private static final String HASH=new com.example.online_reader.config.PasswordConfiguration().passwordEncoder().encode("Synthetic-reader-password");
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired BookRepository books;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookReader reader;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PrivateBookStorage storage;
    String root;
    User owner;
    @BeforeEach void user(){root=((Path)org.springframework.test.util.ReflectionTestUtils.getField(storage,"root")).toString();owner=users.saveAndFlush(new User("reader-"+UUID.randomUUID(),HASH));}
    MockHttpSession session(User user) {
        var principal=new AccountPrincipal(user);principal.eraseCredentials();
        var auth=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());
        var session=new MockHttpSession();session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new SecurityContextImpl(auth));return session;
    }
    Book book(byte[] source,Book.Format format)throws Exception {
        var key=UUID.randomUUID();
        var b=Book.processing(owner,"Книга <script>","Автор",format,source.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)),key);
        Files.write(Path.of(root,"sources",key+".book"),source,StandardOpenOption.CREATE_NEW);b.markReady();return books.saveAndFlush(b);
    }
    String path(Book b){return "/library/books/"+b.getId();}
    @Test void txtUnicodeFragmentsAreEscapedBoundedAndFontIndependent()throws Exception {
        String source="\ufeff<script>🙂</script>\r\n"+"🙂\r".repeat(9000);
        var b=book(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),Book.Format.TXT);var session=session(owner);
        var result=mvc.perform(get(path(b)+"/read").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",containsString("no-store")))
                .andExpect(header().string("Content-Security-Policy",containsString("default-src 'none'")))
                .andExpect(content().string(containsString("&lt;script&gt;🙂&lt;/script&gt;"))).andReturn();
        var page=(BookReader.Page)result.getModelAndView().getModel().get("page");
        assertThat(page.next()).isEqualTo(16384);assertThat(page.following()).isTrue();
        var next=mvc.perform(get(path(b)+"/read").session(session).param("chapter","text").param("offset","16384")
                .param("fingerprint",page.fingerprint()).param("version","1").param("font","largest")).andExpect(status().isOk()).andReturn();
        var second=(BookReader.Page)next.getModelAndView().getModel().get("page");
        assertThat(second.offset()).isEqualTo(16384);assertThat(second.font()).isEqualTo(BookReader.Font.largest);
        mvc.perform(get(path(b)+"/read").session(session).param("offset","9999999")).andExpect(status().isBadRequest());
        mvc.perform(get(path(b)+"/read").session(session).param("fingerprint","0".repeat(64)).param("version","1")).andExpect(status().isConflict());
        mvc.perform(get(path(b)+"/read").session(session).param("font","evil")).andExpect(status().isBadRequest());
        jdbc.update("update books set title='Новое название',author='Другой автор',version=version+1 where id=?",b.getId());
        mvc.perform(get(path(b)+"/read").session(session).param("fingerprint",page.fingerprint()).param("version","1")).andExpect(status().isOk());
    }
    @Test void ownerLiveFlagsAndStateAreCheckedBeforeStorageEvenForAdminAndHead()throws Exception {
        var b=book("Личный текст".getBytes(java.nio.charset.StandardCharsets.UTF_8),Book.Format.TXT);
        var other=users.saveAndFlush(new User("outsider-"+UUID.randomUUID(),HASH));
        for(boolean admin:List.of(false,true)) {
            if(admin){jdbc.update("update users set role='Admin' where id=?",other.getId());other=users.findById(other.getId()).orElseThrow();}
            clearInvocations(storage);
            for(String route:List.of("/read","/resources/0")) {
                mvc.perform(get(path(b)+route).session(session(other))).andExpect(status().isNotFound());
                mvc.perform(head(path(b)+route).session(session(other))).andExpect(status().isNotFound());
                mvc.perform(get("/library/books/9223372036854775807"+route).session(session(other))).andExpect(status().isNotFound());
            }
            verify(storage,never()).openChannel(any(),anyLong());
        }
        var oldSession=session(owner);jdbc.update("update users set enabled=false where id=?",owner.getId());clearInvocations(storage);
        mvc.perform(get(path(b)+"/read").session(oldSession)).andExpect(status().isForbidden());
        verify(storage,never()).openChannel(any(),anyLong());
        jdbc.update("update users set enabled=true where id=?",owner.getId());
        for(String state:List.of("PROCESSING","DELETING")) {
            jdbc.update("update books set state=? where id=?",state,b.getId());clearInvocations(storage);
            mvc.perform(get(path(b)+"/read").session(session(owner))).andExpect(status().isNotFound());verify(storage,never()).openChannel(any(),anyLong());
        }
    }
    @Test void sameSizeCorruptionAndMissingSourceHaveFixedSafeErrors()throws Exception {
        var b=book("original".getBytes(),Book.Format.TXT);Path source=Path.of(root,"sources",b.getStorageKey()+".book");
        Files.writeString(source,"modified");
        mvc.perform(get(path(b)+"/read").session(session(owner))).andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("Исходный файл повреждён")));
        Files.delete(source);mvc.perform(get(path(b)+"/read").session(session(owner))).andExpect(status().isNotFound());
        mvc.perform(get(path(b)+"/read")).andExpect(status().isFound());
        mvc.perform(post(path(b)+"/read").session(session(owner))).andExpect(status().isForbidden());
    }
    @Test void emptyFirstChapterKeepsTocAndNextChapterNavigation()throws Exception {
        var files=EpubFixtures.content();
        files.put("OPS/book.opf",EpubFixtures.bytes(new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8).replace("</manifest>","<item id='empty' href='empty.xhtml' media-type='application/xhtml+xml'/></manifest>").replace("<spine>","<spine><itemref idref='empty'/>")));
        files.put("OPS/empty.xhtml",EpubFixtures.bytes("<html xmlns='http://www.w3.org/1999/xhtml'><body/></html>"));
        Path temporary=Files.createTempFile("reader-empty-fixture-",".epub");Book b;
        try{EpubFixtures.write(temporary,files);b=book(Files.readAllBytes(temporary),Book.Format.EPUB);}finally{Files.deleteIfExists(temporary);}
        mvc.perform(get(path(b)+"/read").session(session(owner))).andExpect(status().isOk())
                .andExpect(content().string(containsString("В этой главе нет текста"))).andExpect(content().string(containsString("Следующая глава")));
        mvc.perform(get(path(b)+"/read").session(session(owner)).param("chapter","s1")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Синтетическая книга")));
    }
    @Test void epubImageIsPrivateTypedAndPinnedUntilClosed()throws Exception {
        var files=EpubFixtures.content();
        files.put("OPS/book.opf",EpubFixtures.bytes(new String(files.get("OPS/book.opf"),java.nio.charset.StandardCharsets.UTF_8).replace("</manifest>","<item id='image' href='image.png' media-type='image/png'/></manifest>")));
        files.put("OPS/text.xhtml",EpubFixtures.bytes("<html xmlns='http://www.w3.org/1999/xhtml'><body><h1 id='start'>Первая глава</h1><p>Текст <strong>книги</strong></p><img src='image.png' alt='Карта'/><script>evil()</script></body></html>"));
        var image=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",image);files.put("OPS/image.png",image.toByteArray());
        Path temporary=Files.createTempFile("reader-fixture-",".epub");Book b;
        try{EpubFixtures.write(temporary,files);b=book(Files.readAllBytes(temporary),Book.Format.EPUB);}finally{Files.deleteIfExists(temporary);}
        var session=session(owner);
        mvc.perform(get(path(b)+"/read").session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("<strong>")));
        String resource=path(b)+"/resources/0?fingerprint="+b.getSourceSha256()+"&version=1";
        mvc.perform(get(resource).session(session)).andExpect(status().isOk()).andExpect(content().bytes(image.toByteArray()))
                .andExpect(header().string("Content-Type","image/png")).andExpect(header().string("Content-Disposition","inline"))
                .andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Cache-Control",containsString("no-store")));
        mvc.perform(head(resource).session(session)).andExpect(status().isOk()).andExpect(content().bytes(new byte[0]));
        mvc.perform(get(path(b)+"/read").session(session).param("chapter","s0").param("anchor","start")).andExpect(status().isOk());
        mvc.perform(get(path(b)+"/resources/999").session(session).param("fingerprint",b.getSourceSha256()).param("version","1")).andExpect(status().isNotFound());
        var principal=new AccountPrincipal(owner);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        try(var held=reader.image(b.getId(),0,b.getSourceSha256(),1);var second=reader.image(b.getId(),0,b.getSourceSha256(),1)) {
            mvc.perform(get(resource).session(session)).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","2"));
            assertThat(storage.removeIfIdle(b.getStorageKey())).isFalse();assertThat(held.stream().readAllBytes()).isEqualTo(image.toByteArray());
        }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        assertThat(storage.removeIfIdle(b.getStorageKey())).isTrue();
    }
}
