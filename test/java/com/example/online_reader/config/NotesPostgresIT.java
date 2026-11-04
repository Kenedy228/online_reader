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
class NotesPostgresIT {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("notes_test").withUsername("notes_test")
            .withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext context;
    static final java.nio.file.Path STORAGE=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"dostoread-notes-pg-"+UUID.randomUUID());

    @BeforeAll
    static void realApplicationOnDisposableDatabase() {
        var settings = Map.<String, Object>of(
                "spring.datasource.url", DATABASE.getJdbcUrl(),
                "spring.datasource.username", DATABASE.getUsername(),
                "spring.datasource.password", DATABASE.getPassword(),
                "online-reader.storage.root", STORAGE.toString(),
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

    private static final String HASH=new PasswordConfiguration().passwordEncoder().encode("synthetic-notes-fixture");
    User owner;Book book;
    @BeforeEach void fixtures()throws Exception {
        owner=context.getBean(UserRepository.class).saveAndFlush(new User("notes-"+UUID.randomUUID(),HASH));
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("abcdefg".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        book=Book.processing(owner,"Заметки","",Book.Format.TXT,7,hash,UUID.randomUUID());book.markReady();book=context.getBean(BookRepository.class).saveAndFlush(book);
        java.nio.file.Files.writeString(STORAGE.resolve("sources").resolve(book.getStorageKey()+".book"),"abcdefg");authenticate();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void authenticate(){var p=new AccountPrincipal(owner);p.eraseCredentials();SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p,null,p.getAuthorities()));}
    com.example.online_reader.book.progress.ReadingPosition position(){return new com.example.online_reader.book.progress.ReadingPosition("text",2,book.getSourceSha256(),1);}
    String proof(){return context.getBean(com.example.online_reader.book.progress.RangeProof.class).issue(owner.getId(),book.getId(),position(),0,7);}
    com.example.online_reader.book.notes.NoteService service(){return context.getBean(com.example.online_reader.book.notes.NoteService.class);}
    Long create(String text){return service().create(book.getId(),text,position(),proof());}
    @Test void twoEditsHaveOneRevisionWinnerWithoutOverwritingTheOther()throws Exception {
        Long note=create("Начало");var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->editAfter(start,note,"Первый"));var second=executor.submit(()->editAfter(start,note,"Второй"));start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isNotEqualTo(second.get(10,TimeUnit.SECONDS));
        }
        var saved=service().get(book.getId(),note);assertThat(saved.version()).isEqualTo(1);assertThat(saved.text()).isIn("Первый","Второй");
    }
    boolean editAfter(CountDownLatch start,Long note,String text)throws Exception {
        start.await();authenticate();try{service().edit(book.getId(),note,text,0);return true;}
        catch(com.example.online_reader.book.notes.NoteService.Failure conflict){assertThat(conflict.code()).isEqualTo("note_conflict");return false;}
        finally{SecurityContextHolder.clearContext();}
    }
    @Test void lastQuotaSlotHasOneWinnerAndPagesHaveStableIdOrder()throws Exception {
        var jdbc=context.getBean(JdbcTemplate.class);
        jdbc.update("insert into book_notes(book_id,owner_id,note_text,chapter,position_offset,fingerprint,representation_version) select ?,?,'fixture '||n,'text',2,?,1 from generate_series(1,199) n",book.getId(),owner.getId(),book.getSourceSha256());
        String proof=proof();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->createAfter(start,proof,"Первый"));var second=executor.submit(()->createAfter(start,proof,"Второй"));start.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isNotEqualTo(second.get(10,TimeUnit.SECONDS));
        }
        assertThat(service().list(book.getId(),0,20).getTotalElements()).isEqualTo(200);
        assertThatThrownBy(()->create("Лишняя")).isInstanceOf(com.example.online_reader.book.notes.NoteService.Failure.class).extracting(e->((com.example.online_reader.book.notes.NoteService.Failure)e).code()).isEqualTo("note_limit");
        jdbc.update("update book_notes set created_at=timestamp with time zone '2026-01-01 00:00:00+00' where book_id=?",book.getId());
        var first=service().list(book.getId(),0,20);var second=service().list(book.getId(),1,20);
        assertThat(first.getContent()).extracting(com.example.online_reader.book.notes.NoteService.View::id).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(first.getContent()).extracting(com.example.online_reader.book.notes.NoteService.View::id).doesNotContainAnyElementsOf(second.map(com.example.online_reader.book.notes.NoteService.View::id).getContent());
        service().delete(book.getId(),first.getContent().getFirst().id(),0);create("Место освободилось");
    }
    boolean createAfter(CountDownLatch start,String proof,String text)throws Exception {
        start.await();authenticate();try{service().create(book.getId(),text,position(),proof);return true;}
        catch(com.example.online_reader.book.notes.NoteService.Failure limit){assertThat(limit.code()).isEqualTo("note_limit");return false;}
        finally{SecurityContextHolder.clearContext();}
    }
    @Test void noteTextAndLocatorSurviveApplicationRestartAndUnicodeDatabaseLength() {
        String text="🙂".repeat(2000);Long note=create(text);
        assertThat(context.getBean(JdbcTemplate.class).queryForObject("select length(note_text) from book_notes where id=?",Integer.class,note)).isEqualTo(2000);
        context.close();context=null;realApplicationOnDisposableDatabase();authenticate();
        var saved=service().get(book.getId(),note);assertThat(saved.text()).isEqualTo(text);assertThat(saved.position()).isEqualTo(position());assertThat(saved.version()).isZero();
        service().edit(book.getId(),note,"После перезапуска",0);assertThat(service().get(book.getId(),note).version()).isEqualTo(1);
    }
    @Test void databaseRejectsCrossOwnerAndInvalidRowsAndCascadesOnPhysicalDelete() {
        Long note=create("Личный текст");var jdbc=context.getBean(JdbcTemplate.class);var other=context.getBean(UserRepository.class).saveAndFlush(new User("other-"+UUID.randomUUID(),HASH));
        assertThatThrownBy(()->jdbc.update("update book_notes set owner_id=? where id=?",other.getId(),note)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update book_notes set note_text=? where id=?","🙂".repeat(2001),note)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(String sql:List.of("update book_notes set note_text=repeat(' ',2001)||'x' where id=?","update book_notes set version=-1 where id=?","update book_notes set position_offset=-1 where id=?","update book_notes set chapter='bad' where id=?","update book_notes set fingerprint='bad' where id=?","update book_notes set representation_version=0 where id=?"))
            assertThatThrownBy(()->jdbc.update(sql,note)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("delete from books where id=?",book.getId());assertThat(jdbc.queryForObject("select count(*) from book_notes where id=?",Integer.class,note)).isZero();
    }
    @Test void blockedNoteCreateCannotRunAfterTheBookDeletionCommit()throws Exception {
        Long note=create("До удаления");String proof=proof();
        try(var connection=java.sql.DriverManager.getConnection(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());var executor=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);int blocker;
            try(var query=connection.createStatement();var rows=query.executeQuery("select pg_backend_pid()")){rows.next();blocker=rows.getInt(1);}
            try(var query=connection.prepareStatement("select id from books where id=? for update")){query.setLong(1,book.getId());query.executeQuery().close();}
            var result=executor.submit(()->{authenticate();try{service().create(book.getId(),"После удаления",position(),proof);return 201;}
                catch(org.springframework.web.server.ResponseStatusException expected){return expected.getStatusCode().value();}finally{SecurityContextHolder.clearContext();}});
            var jdbc=context.getBean(JdbcTemplate.class);long deadline=System.nanoTime()+800_000_000L;boolean waiting=false;
            while(System.nanoTime()<deadline){if(jdbc.queryForObject("select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))",Integer.class,blocker)>0){waiting=true;break;}Thread.sleep(5);}
            assertThat(waiting).as("writer waits on owned book lock").isTrue();
            try(var delete=connection.prepareStatement("delete from book_notes where book_id=?")){delete.setLong(1,book.getId());delete.executeUpdate();}
            try(var update=connection.prepareStatement("update books set state='DELETING',version=version+1 where id=?")){update.setLong(1,book.getId());update.executeUpdate();}
            connection.commit();assertThat(result.get(5,TimeUnit.SECONDS)).isEqualTo(404);assertThat(jdbc.queryForObject("select count(*) from book_notes where book_id=?",Integer.class,book.getId())).isZero();
        }
    }
}
