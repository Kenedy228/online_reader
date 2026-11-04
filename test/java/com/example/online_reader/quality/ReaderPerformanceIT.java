package com.example.online_reader.quality;

import com.example.online_reader.book.files.BookFormatValidator;
import com.example.online_reader.book.model.Book;
import com.example.online_reader.config.PasswordConfiguration;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.Capability;
import org.apache.commons.compress.archivers.tar.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in control rig. Image pulls, fixture creation and warm-up are outside measured requests. */
class ReaderPerformanceIT {
    static final String JRE = "eclipse-temurin:21-jre-noble@sha256:161310cd5bd41fd549d19cf9f90b614897cb4a1532555c613b43b7e0628fe5d6";
    static final long MEMORY = 2L * 1024 * 1024 * 1024;
    static final String OWNER_LABEL = "dostoread.performance-owner";

    @Test void nominalTenUserControlRig() throws Exception {
        var fixture = new ReaderE2EIT();
        Files.createDirectories(fixture.root);
        Files.setPosixFilePermissions(fixture.root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        fixture.fixtures();
        var docker = DockerClientFactory.instance().client();
        String owner = UUID.randomUUID().toString();
        String volume = docker.createVolumeCmd().withName("dostoread-performance-" + owner)
                .withLabels(Map.of(OWNER_LABEL, owner)).exec().getName();
        try (var network = Network.newNetwork();
             var database = fixture.database.withNetwork(network).withNetworkAliases("postgres")
                     .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withNanoCPUs(1_000_000_000L).withMemory(MEMORY))) {
            database.start();
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            Path archive = fixture.root.resolve("sources.tar");
            fixture.browserData.put("users", seed(database, fixture.root, archive));
            // The helper touches only our labelled disposable volume, before the non-root app starts.
            try (var helper = new GenericContainer<>(DockerImageName.parse(JRE))
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withBinds(new Bind(volume, new Volume("/data"))))
                    .withCopyFileToContainer(MountableFile.forHostPath(archive), "/tmp/sources.tar")
                    .withCopyFileToContainer(MountableFile.forHostPath("target/online-reader-0.0.1-SNAPSHOT.jar"), "/tmp/application.jar")
                    .withCommand("sh", "-c", "mkdir /data/books && tar -xf /tmp/sources.tar -C /data/books && mv /tmp/application.jar /data/application.jar && chown -R 10001:10001 /data && chmod 700 /data /data/books /data/books/sources && echo FIXTURE_READY")
                    .waitingFor(Wait.forLogMessage(".*FIXTURE_READY.*", 1))) {
                helper.start();
            }
            try (var app = new GenericContainer<>(DockerImageName.parse(JRE))
                    .withNetwork(network).withExposedPorts(8080)
                    .withEnv("SPRING_PROFILES_ACTIVE", "local")
                    .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/reader_e2e")
                    .withEnv("SPRING_DATASOURCE_USERNAME", database.getUsername()).withEnv("SPRING_DATASOURCE_PASSWORD", database.getPassword())
                    .withEnv("ONLINE_READER_STORAGE_ROOT", "/data/books").withEnv("SERVER_ADDRESS", "0.0.0.0")
                    .withEnv("SERVER_PORT", "8080")
                    .withCreateContainerCmdModifier(cmd -> {
                        cmd.withUser("10001:10001");
                        cmd.getHostConfig().withNanoCPUs(1_000_000_000L).withMemory(MEMORY).withReadonlyRootfs(true)
                                .withBinds(new Bind(volume, new Volume("/data")))
                                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=128m,mode=1777"))
                                .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges:true"));
                    })
                    .withCommand("java", "-jar", "/data/application.jar")
                    .waitingFor(Wait.forHttp("/login").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))) {
                app.start();
                fixture.port = app.getMappedPort(8080);
                var runtime = app.execInContainer("java", "-XshowSettings:system", "-version");
                Files.writeString(fixture.root.resolve("runtime.txt"), runtime.getStdout() + runtime.getStderr());
                var appLimits = docker.inspectContainerCmd(app.getContainerId()).exec().getHostConfig();
                var dbLimits = docker.inspectContainerCmd(database.getContainerId()).exec().getHostConfig();
                assertThat(appLimits.getNanoCPUs()).isEqualTo(1_000_000_000L);
                assertThat(dbLimits.getNanoCPUs()).isEqualTo(1_000_000_000L);
                assertThat(appLimits.getMemory()).isEqualTo(MEMORY);
                assertThat(dbLimits.getMemory()).isEqualTo(MEMORY);
                fixture.json.writeValue(fixture.root.resolve("rig.json").toFile(), Map.of(
                        "appImage", JRE, "databaseImage", ReaderE2EIT.POSTGRES,
                        "appNanoCpus", appLimits.getNanoCPUs(), "appMemory", appLimits.getMemory(),
                        "databaseNanoCpus", dbLimits.getNanoCPUs(), "databaseMemory", dbLimits.getMemory(),
                        "storage", "owned Docker local volume; underlying physical medium is an operator prerequisite",
                        "osArch", System.getProperty("os.name") + " " + System.getProperty("os.arch")));
                try { fixture.runBrowser("performance.cjs"); }
                finally { Files.writeString(fixture.root.resolve("application.log"), app.getLogs()); }
            }
        } finally {
            // Never remove a volume selected by a user/env/path; verify the exact creation label.
            var actual = docker.inspectVolumeCmd(volume).exec();
            assertThat(actual.getLabels()).containsEntry(OWNER_LABEL, owner);
            docker.removeVolumeCmd(volume).exec();
        }
    }

    List<Map<String, Object>> seed(PostgreSQLContainer<?> database, Path root, Path archive) throws Exception {
        byte[] txt = "Большая книга 🙂 строка текста\n".repeat(180_000).getBytes(StandardCharsets.UTF_8);
        assertThat(txt.length).isBetween(9 * 1024 * 1024, 10 * 1024 * 1024);
        Files.write(root.resolve("large.txt"), txt);
        try (var zip = new ZipOutputStream(Files.newOutputStream(root.resolve("large.epub")));
             var small = new ZipFile(root.resolve("valid.epub").toFile())) {
            for (var entries = small.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                ReaderE2EIT.entry(zip, entry.getName(), small.getInputStream(entry).readAllBytes());
            }
            // Real uncompressed payload, not a ZIP bomb or an invalid central-directory size.
            for (int i = 0; i < 3; i++) ReaderE2EIT.entry(zip, "padding" + i + ".bin", new byte[3 * 1024 * 1024]);
        }
        byte[] epub = Files.readAllBytes(root.resolve("large.epub"));
        assertThat(epub.length).isBetween(9 * 1024 * 1024, 10 * 1024 * 1024);
        var validator = new BookFormatValidator();
        validator.validate(root.resolve("large.txt"), Book.Format.TXT);
        validator.validate(root.resolve("large.epub"), Book.Format.EPUB);
        String hash = new PasswordConfiguration().passwordEncoder().encode("Synthetic-preview-password");
        var users = new ArrayList<Map<String, Object>>();
        try (var db = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
             var tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
            db.setAutoCommit(false);
            for (int user = 0; user < 10; user++) {
                int id;
                try (var insert = db.prepareStatement("insert into users(username,password,email) values (?,?,?) returning id")) {
                    insert.setString(1, "performance-" + user); insert.setString(2, hash); insert.setString(3, "performance-" + user + "@example.invalid");
                    try (var rows = insert.executeQuery()) { rows.next(); id = rows.getInt(1); }
                }
                var cards = new ArrayList<Map<String, Object>>();
                for (int book = 0; book < 100; book++) {
                    byte[] source = book == 0 ? txt : book == 1 ? epub : ("Уникальная книга " + user + " " + book).getBytes(StandardCharsets.UTF_8);
                    String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
                    String key = UUID.randomUUID().toString(); long bookId;
                    try (var insert = db.prepareStatement("insert into books(owner_id,title,format,state,original_size,source_sha256,storage_key) values (?, ?, ?, 'READY', ?, ?, ?::uuid) returning id")) {
                        insert.setInt(1, id); insert.setString(2, "Книга " + book); insert.setString(3, book == 1 ? "EPUB" : "TXT");
                        insert.setInt(4, source.length); insert.setString(5, fingerprint); insert.setString(6, key);
                        try (var rows = insert.executeQuery()) { rows.next(); bookId = rows.getLong(1); }
                    }
                    var entry = new TarArchiveEntry("sources/" + key + ".book");
                    entry.setSize(source.length); entry.setMode(0600); entry.setUserId(10001); entry.setGroupId(10001);
                    tar.putArchiveEntry(entry); tar.write(source); tar.closeArchiveEntry();
                    long firstNote = 0;
                    for (int note = 0; note < 10; note++) {
                        try (var insert = db.prepareStatement("insert into book_notes(book_id,owner_id,note_text,chapter,position_offset,fingerprint,representation_version) values (?,?,?,?,0,?,1) returning id")) {
                            insert.setLong(1, bookId); insert.setInt(2, id); insert.setString(3, "Мысль " + note); insert.setString(4, book == 1 ? "s0" : "text"); insert.setString(5, fingerprint);
                            try (var rows = insert.executeQuery()) { rows.next(); if (note == 0) firstNote = rows.getLong(1); }
                        }
                    }
                    if (book < 2) cards.add(Map.of("id", bookId, "note", firstNote, "format", book == 1 ? "EPUB" : "TXT", "size", source.length));
                }
                users.add(Map.of("username", "performance-" + user, "cards", cards));
            }
            db.commit();
            try (var query = db.createStatement(); var rows = query.executeQuery("select (select count(*) from users), (select count(*) from books), (select count(*) from book_notes)")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(10); assertThat(rows.getInt(2)).isEqualTo(1000); assertThat(rows.getInt(3)).isEqualTo(10000);
            }
        }
        return users;
    }
}
