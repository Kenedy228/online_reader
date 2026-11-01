package com.example.online_reader.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCredentialsTest {

    @ParameterizedTest
    @CsvSource({
            "application-dev.properties, spring.datasource.url, SPRING_DATASOURCE_URL",
            "application-dev.properties, spring.datasource.username, SPRING_DATASOURCE_USERNAME",
            "application-dev.properties, spring.datasource.password, SPRING_DATASOURCE_PASSWORD",
            "application.properties, spring.mail.username, SPRING_MAIL_USERNAME",
            "application.properties, spring.mail.password, SPRING_MAIL_PASSWORD"
    })
    void runtimeConfigurationResolvesExternalValues(String resource, String key, String variable) throws IOException {
        String probe = UUID.randomUUID().toString();
        var environment = new MockEnvironment().withProperty(variable, probe);
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:" + resource));

        // A failed regression must not print an old credential in an assertion diff.
        assertThat(probe.equals(environment.getRequiredProperty(key)))
                .as("%s resolves %s from its environment variable", resource, key).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "application-dev.properties, spring.datasource.url",
            "application-dev.properties, spring.datasource.username",
            "application-dev.properties, spring.datasource.password",
            "application.properties, spring.mail.username",
            "application.properties, spring.mail.password"
    })
    void runtimeConfigurationHasNoLiteralFallback(String resource, String key) throws IOException {
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:" + resource));

        assertThatThrownBy(() -> environment.getRequiredProperty(key))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
