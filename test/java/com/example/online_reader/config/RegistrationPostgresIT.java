package com.example.online_reader.config;

import com.example.online_reader.OnlineReaderApplication;
import com.example.online_reader.user.DTO.RegistrationForm;
import com.example.online_reader.user.repository.UserRepository;
import com.example.online_reader.user.service.RegistrationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real registration service/transactions on owned PostgreSQL, with no developer environment. */
@Testcontainers
class RegistrationPostgresIT {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("registration_test").withUsername("registration_test")
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

    @ParameterizedTest
    @ValueSource(strings = {"username", "email"})
    void concurrentRegistrationsHaveExactlyOneWinnerWithoutUpdatingIt(String collision) throws Exception {
        var first = form("first");
        var second = form("second");
        if (collision.equals("username")) second.setUsername(first.getUsername());
        else second.setEmail(first.getEmail().toUpperCase(java.util.Locale.ROOT));
        var service = context.getBean(RegistrationService.class);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {start.await(); return service.register(first);});
            var b = executor.submit(() -> {start.await(); return service.register(second);});
            start.countDown();
            boolean aWon = a.get(20, TimeUnit.SECONDS);
            boolean bWon = b.get(20, TimeUnit.SECONDS);
            assertThat(aWon).isNotEqualTo(bWon);
            var winner = aWon ? first : second;
            var loser = aWon ? second : first;
            var saved = context.getBean(UserRepository.class).findUserByUsername(winner.getUsername());
            assertThat(saved.getEmail()).isEqualTo(winner.getEmail());
            var encoder = context.getBean(PasswordEncoder.class);
            assertThat(encoder.matches(winner.getPassword(), saved.getPasswordHash())).isTrue();
            assertThat(encoder.matches(loser.getPassword(), saved.getPasswordHash())).isFalse();
            assertThat(saved.getEnabled()).isTrue();
            assertThat(saved.getAccountNonLocked()).isTrue();
            assertThat(saved.getRole().name()).isEqualTo("User");
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select count(*) from users where username in (?,?)", Integer.class,
                    first.getUsername(), second.getUsername())).isEqualTo(1);
            String hashBefore = saved.getPasswordHash();
            assertThat(service.register(loser)).isFalse();
            assertThat(context.getBean(UserRepository.class).findUserByUsername(winner.getUsername()).getPasswordHash())
                    .isEqualTo(hashBefore);
            // A conflict is a normal result, not an aborted transaction poisoning later registrations.
            assertThat(service.register(form("unrelated"))).isTrue();
        }
    }

    private static RegistrationForm form(String suffix) {
        var form = new RegistrationForm();
        form.setUsername("pg-" + UUID.randomUUID());
        form.setEmail("pg-" + UUID.randomUUID() + "@example.test");
        form.setPassword("synthetic-postgres-password-" + suffix);
        form.setConfirmPassword(form.getPassword());
        return form;
    }
}
