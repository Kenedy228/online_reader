package com.example.online_reader.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Reject unsafe runtime configuration before any datasource or mail beans exist. */
public final class RuntimeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1 || !Set.of("local", "test", "production").contains(profiles[0])) {
            throw new IllegalStateException("Choose exactly one profile: local, test, production");
        }
        rejectAlternateSettings(environment);
        if ("test".equals(profiles[0])) {
            try {
                // This file exists only on the test classpath. Its priority prevents exported
                // runtime credentials and CLI settings from redirecting tests to a real service.
                environment.getPropertySources().addFirst(new ResourcePropertySource(
                        "isolatedTestRuntime", "classpath:application-test.properties"));
            } catch (IOException exception) {
                throw new IllegalStateException("The test profile requires the test classpath");
            }
            return;
        }

        validate(environment, "spring.datasource.url", value -> {
            if (!value.startsWith("jdbc:postgresql://")) {
                return false;
            }
            var uri = java.net.URI.create(value.substring("jdbc:".length()));
            String query = uri.getQuery();
            return uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getPath() != null && uri.getPath().length() > 1
                    && (query == null || !query.matches("(?i).*(^|&)(user|password)=.*"));
        });
        required(environment, "spring.datasource.username");
        required(environment, "spring.datasource.password");
        expected(environment, "spring.datasource.driver-class-name", "org.postgresql.Driver");
        expected(environment, "spring.datasource.type", "com.zaxxer.hikari.HikariDataSource");
        expected(environment, "spring.jpa.hibernate.ddl-auto", "validate");
        expected(environment, "spring.jpa.generate-ddl", "false");
        expected(environment, "spring.sql.init.mode", "never");
        expected(environment, "spring.flyway.enabled", "true");
        expected(environment, "spring.flyway.locations", "classpath:db/migration");
        expected(environment, "spring.flyway.clean-disabled", "true");
        expected(environment, "spring.flyway.baseline-on-migrate", "false");
        expected(environment, "spring.flyway.validate-on-migrate", "true");
        expected(environment, "spring.h2.console.enabled", "false");
        expected(environment, "spring.jpa.show-sql", "false");
        expected(environment, "logging.level.org.hibernate.SQL", "OFF");
        expected(environment, "logging.level.org.hibernate.orm.jdbc.bind", "OFF");
        number(environment, "online-reader.registration.max-attempts", 1, 100);
        number(environment, "online-reader.registration.max-clients", 1, 4096);
        validate(environment, "online-reader.registration.window", value -> {
            var window = DurationStyle.detectAndParse(value);
            return window.compareTo(java.time.Duration.ofSeconds(1)) >= 0
                    && window.compareTo(java.time.Duration.ofMinutes(15)) <= 0;
        });
        number(environment, "online-reader.auth.max-attempts", 1, 100);
        number(environment, "online-reader.auth.max-clients", 1, 4096);
        validate(environment, "online-reader.auth.window", value -> {
            var window = DurationStyle.detectAndParse(value);
            return window.compareTo(java.time.Duration.ofSeconds(1)) >= 0
                    && window.compareTo(java.time.Duration.ofMinutes(15)) <= 0;
        });
        expected(environment, "server.servlet.session.cookie.name", "JSESSIONID");
        expected(environment, "server.servlet.session.cookie.http-only", "true");
        expected(environment, "server.servlet.session.cookie.same-site", "lax");
        expected(environment, "server.servlet.session.cookie.secure", Boolean.toString("production".equals(profiles[0])));
        expected(environment, "server.servlet.session.tracking-modes", "cookie");
        expected(environment, "server.servlet.session.persistent", "false");
        expected(environment, "server.forward-headers-strategy", "none");
        validate(environment, "server.servlet.session.timeout", value -> {
            var timeout = DurationStyle.detectAndParse(value);
            return timeout.compareTo(java.time.Duration.ofMinutes(1)) >= 0
                    && timeout.compareTo(java.time.Duration.ofHours(1)) <= 0;
        });
        expected(environment, "debug", "false");
        expected(environment, "trace", "false");
        expected(environment, "server.error.include-stacktrace", "never");
        expected(environment, "server.error.include-message", "never");
        expected(environment, "server.error.include-binding-errors", "never");
        expected(environment, "server.error.include-exception", "false");
        expected(environment, "server.error.path", "/error");
        expected(environment, "server.error.whitelabel.enabled", "false");

        // Both supported runtime profiles use a local Mailpit sink, never a delivery provider.
        String host = required(environment, "spring.mail.host");
        check(Set.of("127.0.0.1", "localhost", "::1", "mailpit").contains(host), "spring.mail.host");
        number(environment, "spring.mail.port", 1, 65535);
        expected(environment, "spring.mail.protocol", "smtp");
        expected(environment, "spring.mail.properties.mail.smtp.auth", "false");
        expected(environment, "spring.mail.properties.mail.smtp.ssl.enable", "false");
        expected(environment, "spring.mail.properties.mail.smtp.starttls.enable", "false");
        expected(environment, "spring.mail.properties.mail.smtp.starttls.required", "false");
        for (String timeout : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
            number(environment, "spring.mail.properties.mail.smtp." + timeout, 1, Integer.MAX_VALUE);
        }
        number(environment, "spring.datasource.hikari.connection-timeout", 250, Integer.MAX_VALUE);
        number(environment, "spring.datasource.hikari.validation-timeout", 250, Integer.MAX_VALUE);
        check(Long.parseLong(required(environment, "spring.datasource.hikari.validation-timeout"))
                < Long.parseLong(required(environment, "spring.datasource.hikari.connection-timeout")),
                "spring.datasource.hikari.validation-timeout");
        validate(environment, "server.tomcat.connection-timeout",
                value -> DurationStyle.detectAndParse(value).toMillis() > 0);
        number(environment, "server.port", 1, 65535);
        long fileSize = size(environment, "spring.servlet.multipart.max-file-size");
        long requestSize = size(environment, "spring.servlet.multipart.max-request-size");
        check(requestSize >= fileSize, "spring.servlet.multipart.max-request-size");
        validate(environment, "online-reader.storage.root", value -> {
            Path root = Path.of(value);
            return !"production".equals(profiles[0]) || root.isAbsolute();
        });
    }

    private static void rejectAlternateSettings(ConfigurableEnvironment environment) {
        var binder = Binder.get(environment);
        // Boot/Hikari/JPA have additional connection and schema configuration paths.
        // This project supports the documented datasource and finite timeout settings only.
        for (String key : new String[]{"spring.datasource.jndi-name", "spring.mail.jndi-name",
                "spring.jpa.properties", "spring.datasource.hikari", "spring.flyway"}) {
            boolean unsupported;
            try {
                if (key.endsWith("jndi-name")) {
                    unsupported = binder.bind(key, String.class).isBound();
                } else {
                    var values = binder.bind(key, Bindable.mapOf(String.class, String.class))
                            .orElse(java.util.Map.of());
                    Set<String> allowed = switch (key) {
                        case "spring.datasource.hikari" -> Set.of("connectiontimeout", "validationtimeout");
                        case "spring.flyway" -> Set.of("enabled", "locations", "cleandisabled",
                                "baselineonmigrate", "validateonmigrate");
                        default -> Set.of();
                    };
                    unsupported = values.keySet().stream().anyMatch(name -> !allowed.contains(
                            name.replace("-", "").toLowerCase(java.util.Locale.ROOT)));
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Unsupported runtime setting: " + key);
            }
            if (unsupported) {
                throw new IllegalStateException("Unsupported runtime setting: " + key);
            }
        }
    }

    private static String required(ConfigurableEnvironment environment, String key) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (IllegalArgumentException exception) {
            throw invalid(key);
        }
        check(value != null && !value.isBlank(), key);
        return value;
    }

    private static void expected(ConfigurableEnvironment environment, String key, String value) {
        check(value.equalsIgnoreCase(required(environment, key)), key);
    }

    private static void number(ConfigurableEnvironment environment, String key, long min, long max) {
        validate(environment, key, value -> {
            long number = Long.parseLong(value);
            return number >= min && number <= max;
        });
    }

    private static long size(ConfigurableEnvironment environment, String key) {
        validate(environment, key, value -> DataSize.parse(value).toBytes() > 0);
        return DataSize.parse(required(environment, key)).toBytes();
    }

    private static void validate(ConfigurableEnvironment environment, String key,
                                 java.util.function.Predicate<String> predicate) {
        String value = required(environment, key);
        try {
            check(predicate.test(value), key);
        } catch (RuntimeException exception) {
            // Parser exceptions may include credentials or arbitrary supplied configuration.
            throw invalid(key);
        }
    }

    private static void check(boolean valid, String key) {
        if (!valid) {
            throw invalid(key);
        }
    }

    private static IllegalStateException invalid(String key) {
        return new IllegalStateException("Invalid runtime setting: " + key);
    }
}
