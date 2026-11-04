package com.example.online_reader.quality;

import com.example.online_reader.OnlineReaderApplication;
import com.example.online_reader.config.PasswordConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs real browser requests against an owned runtime app, PostgreSQL and private source files. */
class ReaderE2EIT {
    static final String POSTGRES = "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5";
    static final String MAILPIT = "axllent/mailpit:v1.33.0@sha256:9b3a463be15354c0f0eba91697d80e3e0d8a5623ef0e627cacc836bc17fd5231";
    final ObjectMapper json = new ObjectMapper();
    final Path root = Path.of("target", "reader-e2e", UUID.randomUUID().toString()).toAbsolutePath();
    final Path storage = root.resolve("storage");
    final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES)
            .asCompatibleSubstituteFor("postgres")).withDatabaseName("reader_e2e").withUsername("reader_e2e")
            .withPassword(UUID.randomUUID().toString());
    final GenericContainer<?> mail = new GenericContainer<>(DockerImageName.parse(MAILPIT))
            .withEnv("MP_DISABLE_VERSION_CHECK", "true").withEnv("MP_SMTP_DISABLE_RDNS", "true")
            .withExposedPorts(1025);
    ServletWebServerApplicationContext application;
    int port;
    Path corrupted;
    byte[] original;
    final Map<String, Object> browserData = new HashMap<>();

    @Test
    void criticalReaderJourneyAndClientRegressions() throws Exception {
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        fixtures();
        try (database; mail) {
            database.start();
            mail.start();
            try {
                startApplication();
                var jdbc = application.getBean(JdbcTemplate.class);
                jdbc.update("insert into users (username,password,enabled,account_non_locked,role) values (?,?,true,true,'Admin')",
                        "preview-admin", new PasswordConfiguration().passwordEncoder().encode("Synthetic-preview-password"));
                for (String suite : List.of("progress.cjs", "notes.cjs", "lifecycle.cjs")) runBrowser(suite);
            } finally {
                if (application != null) application.close();
            }
        }
    }

    void startApplication() {
        var settings = new HashMap<String, Object>();
        settings.put("spring.datasource.url", databaseUrl());
        settings.put("spring.datasource.username", database.getUsername());
        settings.put("spring.datasource.password", database.getPassword());
        settings.put("spring.datasource.hikari.connection-timeout", "1000");
        settings.put("spring.datasource.hikari.validation-timeout", "500");
        settings.put("online-reader.storage.root", storage.toString());
        settings.put("spring.mail.host", "127.0.0.1");
        settings.put("spring.mail.port", mail.getMappedPort(1025).toString());
        settings.put("server.address", "127.0.0.1");
        // Multiple independent browser regressions share one remote address, not production traffic.
        settings.put("online-reader.auth.max-attempts", "100");
        settings.put("online-reader.registration.max-attempts", "100");
        settings.put("logging.level.root", "ERROR");
        settings.put("spring.main.banner-mode", "off");
        var boot = new SpringApplication(OnlineReaderApplication.class);
        boot.setRegisterShutdownHook(false);
        boot.setEnvironment(new StandardEnvironment() {
            @Override protected void customizePropertySources(MutablePropertySources sources) {
                // No operator environment, CLI datasource, test/H2 profile or system property overrides.
                sources.addFirst(new MapPropertySource("owned-fixture", settings));
                sources.addLast(new SystemEnvironmentPropertySource("systemEnvironment", settings));
            }
        });
        // Runtime guards still execute. Only this owned listener uses port0, then retains its port on restart.
        boot.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("owned-listener", Map.of("server.port", Integer.toString(port)))));
        application = (ServletWebServerApplicationContext) boot.run("--spring.profiles.active=local");
        port = application.getWebServer().getPort();
    }

    void runBrowser(String suite) throws Exception {
        Path descriptor = root.resolve("fixture.json");
        var data = new HashMap<>(browserData);
        data.put("base", "http://127.0.0.1:" + port); data.put("root", root.toString());
        json.writeValue(descriptor.toFile(), data);
        var builder = new ProcessBuilder(Path.of("target/frontend/node/node").toAbsolutePath().toString(),
                Path.of("test/browser", suite).toAbsolutePath().toString(), descriptor.toString());
        builder.environment().clear();
        builder.environment().put("HOME", System.getProperty("user.home")); // Playwright's versioned browser cache only.
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.redirectError(root.resolve(suite + ".stderr").toFile());
        var process = builder.start();
        try (var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            var output = executor.submit(() -> {
                try (var reader = process.inputReader(StandardCharsets.UTF_8);
                     var writer = process.outputWriter(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("E2E_CONTROL ")) {
                            control(json.readTree(line.substring(12)));
                            writer.write("OK\n"); writer.flush();
                        } else System.out.println("browser: " + line);
                    }
                }
                return true;
            });
            try {
                long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    // A failed control command must fail immediately, not strand Node waiting for its ACK.
                    if (output.isDone()) output.get();
                    assertThat(System.nanoTime()).as("browser suite timeout: %s", suite).isLessThan(deadline);
                }
                output.get(30, TimeUnit.SECONDS);
                assertThat(process.exitValue()).as("%s; diagnostics: %s", suite,
                        Files.readString(root.resolve(suite + ".stderr"))).isZero();
            } finally {
                var descendants = process.descendants().toList();
                descendants.reversed().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                executor.shutdownNow();
            }
        }
    }

    void control(JsonNode request) throws Exception {
        switch (request.path("op").asText()) {
            case "restart" -> { application.close(); application = null; startApplication(); }
            case "corrupt" -> {
                assertThat(original).isNull();
                // Browser supplies only an ID. Resolve the generated key through the owned database first.
                String key = application.getBean(JdbcTemplate.class).queryForObject(
                        "select b.storage_key::text from books b join users u on u.id=b.owner_id where b.id=? and u.username='lifecycle-owner'",
                        String.class, request.path("book").asLong());
                corrupted = storage.resolve("sources").resolve(UUID.fromString(key) + ".book");
                original = Files.readAllBytes(corrupted);
                byte[] altered = original.clone(); altered[0] ^= 1;
                Files.write(corrupted, altered);
            }
            case "restore" -> { assertThat(original).isNotNull(); Files.write(corrupted, original); original = null; }
            case "database-stop" -> database.getDockerClient().stopContainerCmd(database.getContainerId())
                    .withTimeout(1).exec();
            case "database-recover" -> {
                application.close(); application = null;
                database.getDockerClient().startContainerCmd(database.getContainerId()).exec();
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (true) {
                    try (var connection = DriverManager.getConnection(databaseUrl(), database.getUsername(), database.getPassword())) {
                        if (connection.isValid(1)) break;
                    } catch (java.sql.SQLException expected) {
                        if (System.nanoTime() >= deadline) throw expected;
                        Thread.sleep(100);
                    }
                }
                startApplication();
            }
            default -> throw new IllegalArgumentException("Unknown owned fixture command");
        }
    }

    String databaseUrl() {
        // Docker can assign a new random host port when the same stopped container starts again.
        var bindings = database.getDockerClient().inspectContainerCmd(database.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432));
        return "jdbc:postgresql://127.0.0.1:" + bindings[0].getHostPortSpec() + "/reader_e2e";
    }

    void fixtures() throws IOException {
        Files.writeString(root.resolve("valid.txt"), "\ufeff" + "Строка 🙂 кириллица и перенос\r\n".repeat(1200));
        try (var zip = new ZipOutputStream(Files.newOutputStream(root.resolve("valid.epub")))) {
            entry(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.UTF_8));
            for (String name : List.of("META-INF/container.xml", "OPS/book.opf", "OPS/nav.xhtml", "OPS/one.xhtml", "OPS/two.xhtml")) {
                String xml = Files.readString(Path.of("test/browser/fixtures", name.replace('/', '_')));
                xml = xml.replace("<!--LONG-->", "<p>Строка 🙂 кириллица и перенос.</p>".repeat(1000));
                entry(zip, name, xml.getBytes(StandardCharsets.UTF_8));
            }
            entry(zip, "OPS/image.png", Base64.getMimeDecoder().decode(Files.readString(Path.of("test/browser/fixtures/image.base64"))));
        }
    }

    static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        var entry = new ZipEntry(name);
        var crc = new CRC32(); crc.update(bytes);
        entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCrc(crc.getValue());
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
}
