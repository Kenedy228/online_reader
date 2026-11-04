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
class ProgressPostgresIT {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("progress_test").withUsername("progress_test")
            .withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext context;
    static final java.nio.file.Path STORAGE=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"dostoread-progress-pg-"+UUID.randomUUID());

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

    private static final String HASH=new PasswordConfiguration().passwordEncoder().encode("synthetic-progress-fixture");
    User owner;Book book;
    @BeforeEach void fixtures() {
        owner=context.getBean(UserRepository.class).saveAndFlush(new User("progress-"+UUID.randomUUID(),HASH));
        String hash;
        try{hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("abcdefg".getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}
        book=Book.processing(owner,"Прогресс","",Book.Format.TXT,7,hash,UUID.randomUUID());book.markReady();
        book=context.getBean(BookRepository.class).saveAndFlush(book);
        try{java.nio.file.Files.writeString(STORAGE.resolve("sources").resolve(book.getStorageKey()+".book"),"abcdefg");}catch(java.io.IOException e){throw new AssertionError(e);}
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void authenticate(){var principal=new AccountPrincipal(owner);principal.eraseCredentials();SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));}
    com.example.online_reader.book.progress.ReadingPosition position(int offset){return new com.example.online_reader.book.progress.ReadingPosition("text",offset,book.getSourceSha256(),1);}
    String proof(){return context.getBean(com.example.online_reader.book.progress.RangeProof.class).issue(owner.getId(),book.getId(),position(0),0,7);}
    @Test void blockedProgressWriterRechecksDeletingStateAfterAcquiringTheBookLock()throws Exception {
        String proof=proof();
        try(var connection=java.sql.DriverManager.getConnection(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());var executor=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);int blocker;
            try(var query=connection.createStatement();var rows=query.executeQuery("select pg_backend_pid()")){rows.next();blocker=rows.getInt(1);}
            try(var query=connection.prepareStatement("select id from books where id=? for update")){query.setLong(1,book.getId());query.executeQuery().close();}
            var result=executor.submit(()->{authenticate();try{context.getBean(com.example.online_reader.book.progress.ProgressService.class).save(book.getId(),position(1),0,proof);return 201;}
                catch(com.example.online_reader.book.progress.ProgressService.Failure expected){return expected.status();}
                catch(org.springframework.web.server.ResponseStatusException expected){return expected.getStatusCode().value();}finally{SecurityContextHolder.clearContext();}});
            var jdbc=context.getBean(JdbcTemplate.class);long deadline=System.nanoTime()+800_000_000L;boolean waiting=false;
            while(System.nanoTime()<deadline) {
                if(jdbc.queryForObject("select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))",Integer.class,blocker)>0){waiting=true;break;}
                Thread.sleep(5);
            }
            assertThat(waiting).as("writer actually waits on the owned book lock").isTrue();
            try(var update=connection.prepareStatement("update books set state='DELETING',version=version+1 where id=?")){update.setLong(1,book.getId());update.executeUpdate();}
            connection.commit();assertThat(result.get(5,TimeUnit.SECONDS)).isEqualTo(404);
            assertThat(jdbc.queryForObject("select count(*) from reading_progress where book_id=?",Integer.class,book.getId())).isZero();
        }
    }
    @Test void concurrentTabsHaveOneCasWinnerAndDeletionRemovesTheRow()throws Exception {
        String proof=proof();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->saveAfter(start,proof,1));var second=executor.submit(()->saveAfter(start,proof,2));start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isNotEqualTo(second.get(10,TimeUnit.SECONDS));
        }
        authenticate();var saved=context.getBean(com.example.online_reader.book.progress.ProgressService.class).load(book.getId());
        assertThat(saved.revision()).isEqualTo(1);assertThat(saved.position().offset()).isIn(1,2);
        context.getBean(BookService.class).delete(book.getId(),0);
        assertThat(context.getBean(JdbcTemplate.class).queryForObject("select count(*) from reading_progress where book_id=?",Integer.class,book.getId())).isZero();
    }
    boolean saveAfter(CountDownLatch start,String proof,int offset)throws Exception {
        start.await();authenticate();
        try{context.getBean(com.example.online_reader.book.progress.ProgressService.class).save(book.getId(),position(offset),0,proof);return true;}
        catch(com.example.online_reader.book.progress.ProgressService.Failure expected){assertThat(expected.code()).isEqualTo("progress_conflict");return false;}
        finally{SecurityContextHolder.clearContext();}
    }
    @Test void postgresConstraintsRejectCrossOwnerAndInvalidOrDuplicateProgress() {
        authenticate();context.getBean(com.example.online_reader.book.progress.ProgressService.class).save(book.getId(),position(1),0,proof());
        var other=context.getBean(UserRepository.class).saveAndFlush(new User("other-"+UUID.randomUUID(),HASH));var jdbc=context.getBean(JdbcTemplate.class);
        assertThatThrownBy(()->jdbc.update("update reading_progress set owner_id=? where book_id=?",other.getId(),book.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(String sql:List.of("update reading_progress set position_offset=-1 where book_id=?","update reading_progress set revision=0 where book_id=?", "update reading_progress set chapter='invalid' where book_id=?", "update reading_progress set fingerprint='bad' where book_id=?", "update reading_progress set representation_version=2 where book_id=?"))
            assertThatThrownBy(()->jdbc.update(sql,book.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into reading_progress select * from reading_progress where book_id=?",book.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("delete from books where id=?",book.getId());
        assertThat(jdbc.queryForObject("select count(*) from reading_progress where book_id=?",Integer.class,book.getId())).isZero();
    }
    @Test void progressSurvivesApplicationRestartAndOldPageProofDoesNot() {
        authenticate();String oldProof=proof();context.getBean(com.example.online_reader.book.progress.ProgressService.class).save(book.getId(),position(3),0,oldProof);
        context.close();context=null;realApplicationOnDisposableDatabase();authenticate();
        var service=context.getBean(com.example.online_reader.book.progress.ProgressService.class);
        assertThat(service.load(book.getId())).isEqualTo(new com.example.online_reader.book.progress.ProgressService.Snapshot(1,position(3)));
        assertThatThrownBy(()->service.save(book.getId(),position(4),1,oldProof)).isInstanceOf(com.example.online_reader.book.progress.ProgressService.Failure.class)
                .extracting(e->((com.example.online_reader.book.progress.ProgressService.Failure)e).code()).isEqualTo("stale_reader");
        var page=context.getBean(com.example.online_reader.book.reader.BookReader.class).read(book.getId(),"text",3,book.getSourceSha256(),1,com.example.online_reader.book.reader.BookReader.Font.normal,null);
        String fresh=context.getBean(com.example.online_reader.book.progress.RangeProof.class).issue(owner.getId(),book.getId(),position(3),page.offset(),page.next());
        assertThat(service.save(book.getId(),position(4),1,fresh).revision()).isEqualTo(2);
    }
}
