package com.example.online_reader.config;

import com.example.online_reader.OnlineReaderApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeProfilesTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "dev", "local,production", "test,local"})
    void requiresExactlyOneRuntimeProfile(String profiles) {
        assertThatThrownBy(() -> prepare(Map.of(), "--spring.profiles.active=" + profiles))
                .hasMessageContaining("Choose exactly one profile: local, test, production");
    }

    @Test
    void testProfileOverridesHostileRuntimeSettings() {
        var environment = prepare(Map.of(
                "spring.datasource.url", "jdbc:postgresql://invalid.example/never-connect",
                "spring.datasource.driver-class-name", "org.postgresql.Driver",
                "spring.mail.host", "invalid.example",
                "spring.h2.console.enabled", "true"), "--spring.profiles.active=test");
        assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
        assertThat(environment.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.h2.Driver");
        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("spring.h2.console.enabled")).isEqualTo("false");
    }

    @Test
    void localUsesMailpitAndExplicitSchemaValidation() {
        var environment = prepare(database(), "--spring.profiles.active=local");
        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("spring.mail.port")).isEqualTo("1025");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void productionHasSafeErrorAndDatabaseDefaults() {
        var environment = prepare(production(), "--spring.profiles.active=production");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.h2.console.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(environment.getProperty("server.servlet.session.timeout")).isEqualTo("15m");
        assertThat(environment.getProperty("debug")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(environment.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-binding-errors")).isEqualTo("never");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD", "ONLINE_READER_STORAGE_ROOT", "SPRING_MAIL_HOST"})
    void rejectsMissingProductionSettingsBeforeContextCreation(String key) {
        var settings = production();
        settings.remove(key);
        assertThatThrownBy(() -> prepare(settings, "--spring.profiles.active=production"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Invalid runtime setting:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.url=jdbc:postgresql:invalid",
            "spring.datasource.url=jdbc:postgresql://user:fixture-only@127.0.0.1/books",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1/books?password=fixture-only",
            "spring.mail.host=external.invalid", "spring.mail.port=0",
            "spring.mail.port=not-a-number", "spring.mail.port=65536",
            "spring.mail.properties.mail.smtp.timeout=-1", "spring.datasource.hikari.connection-timeout=0",
            "spring.servlet.multipart.max-file-size=-1",
            "spring.servlet.multipart.max-file-size=invalid",
            "server.tomcat.connection-timeout=-1s", "spring.servlet.multipart.max-request-size=1B",
            "online-reader.storage.root=relative", "spring.jpa.hibernate.ddl-auto=update",
            "spring.jpa.generate-ddl=true", "spring.sql.init.mode=always",
            "spring.mail.protocol=smtps", "spring.mail.properties.mail.smtp.ssl.enable=true",
            "spring.mail.properties.mail.smtp.starttls.enable=true",
            "spring.mail.properties.mail.smtp.starttls.required=true", "spring.flyway.enabled=false",
            "spring.flyway.clean-disabled=false", "spring.flyway.baseline-on-migrate=true",
            "spring.flyway.validate-on-migrate=false", "spring.flyway.locations=filesystem:/tmp/unsafe",
            "spring.h2.console.enabled=true", "debug=true", "trace=true",
            "server.servlet.session.cookie.secure=false", "server.servlet.session.cookie.http-only=false",
            "server.servlet.session.cookie.same-site=none", "server.servlet.session.tracking-modes=url",
            "server.servlet.session.timeout=0s", "server.servlet.session.timeout=61m",
            "server.servlet.session.persistent=true", "server.forward-headers-strategy=framework",
            "online-reader.auth.max-attempts=0", "online-reader.auth.max-attempts=101",
            "online-reader.registration.max-attempts=0", "online-reader.registration.max-attempts=101",
            "online-reader.registration.max-clients=0", "online-reader.registration.max-clients=4097",
            "online-reader.registration.window=999ms", "online-reader.registration.window=16m",
            "online-reader.auth.max-clients=4097", "online-reader.auth.window=999ms",
            "online-reader.auth.window=16m", "spring.jpa.show-sql=true", "logging.level.org.hibernate.SQL=DEBUG", "server.error.include-stacktrace=always", "server.error.path=/alternate", "server.error.whitelabel.enabled=true"})
    void rejectsInvalidProductionOverridesWithoutEchoingValues(String override) {
        String[] setting = override.split("=", 2);
        var settings = production();
        settings.put(setting[0], setting[1]);
        assertThatThrownBy(() -> prepare(settings, "--spring.profiles.active=production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid runtime setting: " + setting[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.jndi-name", "spring.mail.jndi-name",
            "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.driver-class-name",
            "spring.datasource.hikari.data-source-properties.url",
            "spring.jpa.properties.hibernate.hbm2ddl.auto",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
            "SPRING_DATASOURCE_HIKARI_JDBCURL", "SPRING_DATASOURCE_JNDINAME",
            "SPRING_MAIL_JNDINAME", "spring.flyway.url", "spring.flyway.user",
            "spring.flyway.password", "spring.flyway.schemas", "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_INIT"})
    void rejectsAlternateConnectionAndSchemaSettings(String key) {
        for (String profile : new String[]{"test", "local", "production"}) {
            var settings = production();
            settings.put(key, "fixture-unsafe-override");
            assertThatThrownBy(() -> prepare(settings, "--spring.profiles.active=" + profile))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Unsupported runtime setting:");
        }
    }

    @Test
    void validatesRelaxedEnvironmentTimeoutNames() {
        var settings = production();
        settings.put("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT", "0");
        assertThatThrownBy(() -> prepare(settings, "--spring.profiles.active=production"))
                .hasMessage("Invalid runtime setting: spring.datasource.hikari.connection-timeout");
    }

    @Test
    void localRejectsExportedExternalSmtp() {
        var settings = database();
        settings.put("spring.mail.host", "smtp.external.invalid");
        assertThatThrownBy(() -> prepare(settings, "--spring.profiles.active=local"))
                .hasMessage("Invalid runtime setting: spring.mail.host");
    }

    private static Map<String, Object> database() {
        var settings = new HashMap<String, Object>();
        settings.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/online_reader");
        settings.put("SPRING_DATASOURCE_USERNAME", "fixture-user");
        settings.put("SPRING_DATASOURCE_PASSWORD", "fixture-only");
        return settings;
    }

    private static Map<String, Object> production() {
        var settings = database();
        settings.put("ONLINE_READER_STORAGE_ROOT", "/tmp/online-reader-fixture-books");
        settings.put("SPRING_MAIL_HOST", "127.0.0.1");
        return settings;
    }

    private static ConfigurableEnvironment prepare(Map<String, Object> settings, String... arguments) {
        var captured = new AtomicReference<ConfigurableEnvironment>();
        var application = new SpringApplication(OnlineReaderApplication.class);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setEnvironment(new org.springframework.core.env.StandardEnvironment() {
            @Override
            protected void customizePropertySources(org.springframework.core.env.MutablePropertySources sources) {
                // Do not inherit developer credentials, JVM test profile, or dotenv in this contract test.
                sources.addFirst(new MapPropertySource("fixture", settings));
                sources.addLast(new org.springframework.core.env.SystemEnvironmentPropertySource(
                        "systemEnvironment", settings));
                sources.addLast(new MapPropertySource("quiet", Map.of(
                        "logging.level.root", "OFF", "spring.main.banner-mode", "off")));
            }
        });
        application.addInitializers((ApplicationContextInitializer<ConfigurableApplicationContext>) context -> {
            captured.set(context.getEnvironment());
            throw new Prepared();
        });
        try {
            application.run(arguments);
        } catch (Prepared expected) {
            return captured.get();
        }
        throw new AssertionError("Context must not be refreshed in configuration tests");
    }

    private static class Prepared extends RuntimeException { }
}
