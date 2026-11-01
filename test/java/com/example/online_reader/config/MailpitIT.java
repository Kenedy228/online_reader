package com.example.online_reader.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Uses the shipped Spring Mail settings; all SMTP targets belong to this test. */
@Testcontainers
class MailpitIT {
    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse(
            "axllent/mailpit:v1.33.0@sha256:9b3a463be15354c0f0eba91697d80e3e0d8a5623ef0e627cacc836bc17fd5231"))
            .withExposedPorts(1025, 8025)
            .withEnv("MP_DISABLE_VERSION_CHECK", "true")
            .withEnv("MP_SMTP_DISABLE_RDNS", "true")
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void springMailReachesMailpitAndInboxCanBeCleared() throws Exception {
        String subject = "mailpit-smoke-" + UUID.randomUUID();
        sender(MAILPIT.getHost(), MAILPIT.getMappedPort(1025)).run(context -> {
            assertThat(context).hasSingleBean(JavaMailSender.class);
            context.getBean(JavaMailSender.class).send(message(subject));
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode messages = JSON.readTree(request("GET", "/api/v1/messages").body());
            assertThat(messages.path("total").asInt()).isEqualTo(1);
            JsonNode summary = messages.path("messages").get(0);
            assertThat(summary.path("Subject").asText()).isEqualTo(subject);
            String id = summary.path("ID").asText();
            JsonNode detail = JSON.readTree(request("GET", "/api/v1/message/" + id).body());
            assertThat(detail.path("Text").asText().stripTrailing()).isEqualTo("Synthetic local SMTP smoke message");
            assertThat(detail.path("From").path("Address").asText()).isEqualTo("sender@example.test");
            assertThat(detail.path("To").get(0).path("Address").asText()).isEqualTo("reader@example.test");
        });
        request("DELETE", "/api/v1/messages");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(JSON.readTree(request("GET", "/api/v1/messages").body())
                        .path("total").asInt()).isZero());
    }

    @Test
    void stalledSmtpFailsWithinConfiguredReadTimeout() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2000);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var sending = executor.submit(() -> sender("127.0.0.1", server.getLocalPort()).run(context ->
                        assertThatThrownBy(() -> context.getBean(JavaMailSender.class).send(message("timeout-smoke")))
                                .isInstanceOf(MailSendException.class)
                                .hasRootCauseInstanceOf(SocketTimeoutException.class)));
                // Accept TCP but never send the SMTP greeting. This tests the real mail.smtp.timeout,
                // rather than an immediate ECONNREFUSED or a timeout invented by a test mock.
                try (var stalled = server.accept()) {
                    assertThat(stalled.isConnected()).isTrue();
                    sending.get(6, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static ApplicationContextRunner sender(String host, int port) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove("systemProperties");
                    sources.remove("systemEnvironment");
                    sources.addFirst(new MapPropertySource("ownedSmtp", Map.of(
                            "SPRING_MAIL_HOST", host, "SPRING_MAIL_PORT", Integer.toString(port))));
                    try {
                        sources.addLast(new ResourcePropertySource("classpath:application.properties"));
                        sources.addLast(new ResourcePropertySource("classpath:application-local.properties"));
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
    }

    private static SimpleMailMessage message(String subject) {
        var message = new SimpleMailMessage();
        message.setFrom("sender@example.test");
        message.setTo("reader@example.test");
        message.setSubject(subject);
        message.setText("Synthetic local SMTP smoke message");
        return message;
    }

    private static HttpResponse<String> request(String method, String path) throws Exception {
        var uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return response;
    }
}
