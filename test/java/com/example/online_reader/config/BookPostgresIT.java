package com.example.online_reader.config;

import com.example.online_reader.OnlineReaderApplication;
import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.book.service.BookService;
import com.example.online_reader.book.DTO.BookMetadataForm;
import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import com.example.online_reader.security.AccountPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class BookPostgresIT {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("library_test").withUsername("library_test")
            .withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext context;

    @BeforeAll
    static void realApplicationOnDisposableDatabase() {
        var settings = Map.<String, Object>of(
                "spring.datasource.url", DATABASE.getJdbcUrl(),
                "spring.datasource.username", DATABASE.getUsername(),
                "spring.datasource.password", DATABASE.getPassword(),
                "server.address", "127.0.0.1",
                "logging.level.root", "WARN", "spring.main.banner-mode", "off");
        var application = new SpringApplication(OnlineReaderApplication.class);
        application.setRegisterShutdownHook(false);
        application.setEnvironment(new StandardEnvironment() {
            @Override
            protected void customizePropertySources(org.springframework.core.env.MutablePropertySources sources) {
                sources.addFirst(new MapPropertySource("fixture", settings));
                sources.addLast(new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", settings));
            }
        });
        // Runtime disallows port0. Only this owned test listener gets an ephemeral port after
        // environment validation; datasource/Flyway/other runtime settings remain unchanged.
        application.addInitializers(appContext -> appContext.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("ephemeral-listener", Map.of("server.port", "0"))));
        context = application.run("--spring.profiles.active=local");
    }

    @AfterAll
    static void closeOwnedContext() { if (context != null) context.close(); }

    private static final String HASH = new PasswordConfiguration().passwordEncoder().encode("synthetic-book-fixture");
    User owner;
    Book book;
    @BeforeEach void fixtures() {
        owner = context.getBean(UserRepository.class).saveAndFlush(new User("book-"+UUID.randomUUID(), HASH));
        book = processing(owner,"Книга"); book.markReady();
        book = context.getBean(BookRepository.class).saveAndFlush(book);
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }
    Book processing(User user,String title) {
        return Book.processing(user,title,"",Book.Format.EPUB,10485760,
                UUID.randomUUID().toString().replace("-", "").repeat(2),UUID.randomUUID());
    }
    void authenticate() {
        var principal = new AccountPrincipal(owner); principal.eraseCredentials();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
    }
    BookMetadataForm form(String title) {
        var form=new BookMetadataForm(); form.setTitle(title);form.setAuthor("");form.setVersion(0L);return form;
    }
    @Test void postgresStoresTwoHundredUnicodeCodePointsAndOwnerScopedPages() {
        authenticate();var service=context.getBean(BookService.class);
        service.edit(book.getId(),form("🙂".repeat(200)));
        assertThat(service.detail(book.getId()).title()).isEqualTo("🙂".repeat(200));
        assertThat(service.list(0,20).getTotalElements()).isEqualTo(1);
        var other=context.getBean(UserRepository.class).saveAndFlush(new User("other-"+UUID.randomUUID(),HASH));
        var principal=new AccountPrincipal(other);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        assertThat(service.list(0,20).getTotalElements()).isZero();
        assertThatThrownBy(()->service.detail(book.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e->((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(404);
    }
    @Test void concurrentMetadataChangesHaveOneWinnerWithoutLostUpdate() throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->updateAfter(start,"Первый"));
            var b=executor.submit(()->updateAfter(start,"Второй"));
            start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)).isNotEqualTo(b.get(15,TimeUnit.SECONDS));
        }
        var saved=context.getBean(BookRepository.class).findById(book.getId()).orElseThrow();
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getTitle()).isIn("Первый","Второй");
    }
    boolean updateAfter(CountDownLatch start,String title) throws Exception {
        start.await();authenticate();
        try {context.getBean(BookService.class).edit(book.getId(),form(title));return true;}
        catch(BookService.Conflict expected){return false;}
        finally{SecurityContextHolder.clearContext();}
    }
    @Test void deletionRetainsSourceReservationAndBlocksMutation() {
        authenticate();var service=context.getBean(BookService.class);
        service.delete(book.getId(),0);service.delete(book.getId(),0);
        assertThatThrownBy(()->service.edit(book.getId(),form("После удаления")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var jdbc=context.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("select original_size from books where id=?",Long.class,book.getId())).isEqualTo(10485760);
        assertThat(jdbc.queryForObject("select state from books where id=?",String.class,book.getId())).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("select storage_key is not null and source_sha256 is not null from books where id=?",Boolean.class,book.getId())).isTrue();
    }
    @Test void schemaRejectsInvalidSourcesAndPreservesOwnerReservations() {
        var jdbc=context.getBean(JdbcTemplate.class);
        for(String sql:List.of("update books set original_size=10485761 where id=?", "update books set original_size=0 where id=?",
                "update books set state='INVALID' where id=?", "update books set format='PDF' where id=?",
                "update books set source_sha256='invalid' where id=?", "update books set version=-1 where id=?",
                "update books set title='' where id=?", "update books set owner_id=-1 where id=?")) {
            assertThatThrownBy(()->jdbc.update(sql,book.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        var candidate=processing(owner,"Резерв");
        var saved=context.getBean(BookRepository.class).saveAndFlush(candidate);
        // A DELETING row still participates in both deduplication and quota accounting.
        jdbc.update("update books set state='DELETING' where id=?",book.getId());
        assertThatThrownBy(()->jdbc.update("update books set source_sha256=(select source_sha256 from books where id=?) where id=?",book.getId(),saved.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var other=context.getBean(UserRepository.class).saveAndFlush(new User("copy-"+UUID.randomUUID(),HASH));
        jdbc.update("update books set owner_id=?,source_sha256=(select source_sha256 from books where id=?) where id=?",other.getId(),book.getId(),saved.getId());
        assertThatThrownBy(()->jdbc.update("update books set storage_key=(select storage_key from books where id=?) where id=?",book.getId(),saved.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from users where id=?",owner.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("insert into books(owner_id,title,format,original_size,source_sha256,storage_key) values (?,'Reserved','TXT',1,?,?)",
                owner.getId(),"a".repeat(64),UUID.randomUUID());
        assertThat(jdbc.queryForObject("select state from books where owner_id=? and title='Reserved'",String.class,owner.getId())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where tablename='books' and indexname='books_owner_created_idx'",Integer.class)).isEqualTo(1);
    }
}
