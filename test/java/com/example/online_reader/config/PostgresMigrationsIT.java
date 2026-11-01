package com.example.online_reader.config;

import com.example.online_reader.actions.model.Action;
import com.example.online_reader.admin.model.Admin;
import com.example.online_reader.user.model.User;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.Connection;
import com.example.online_reader.config.PasswordConfiguration;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in real PostgreSQL contract checks; never uses a developer datasource. */
@Testcontainers
class PostgresMigrationsIT {
    private static final String HASH = new PasswordConfiguration().passwordEncoder().encode("synthetic-migration-fixture");
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("migration_test")
            .withUsername("migration_test")
            .withPassword(UUID.randomUUID().toString());

    @Test
    void migratesEmptyDatabaseAndValidatesJpaMappings() {
        Flyway flyway = migrate("schema_" + UUID.randomUUID().toString().replace("-", ""));
        var settings = new Configuration()
                .addAnnotatedClass(User.class).addAnnotatedClass(Admin.class).addAnnotatedClass(Action.class)
                .setProperty("jakarta.persistence.jdbc.url", DATABASE.getJdbcUrl())
                .setProperty("jakarta.persistence.jdbc.user", DATABASE.getUsername())
                .setProperty("jakarta.persistence.jdbc.password", DATABASE.getPassword())
                .setProperty("hibernate.default_schema", flyway.getConfiguration().getDefaultSchema())
                .setProperty("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .setProperty("hibernate.implicit_naming_strategy",
                        "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy")
                .setProperty("hibernate.hbm2ddl.auto", "validate")
                .setProperty("hibernate.show_sql", "false");
        try (var sessions = settings.buildSessionFactory(); var session = sessions.openSession()) {
            var transaction = session.beginTransaction();
            session.persist(new User("reader", HASH));
            transaction.commit();
            assertThat(session.createQuery("select count(u) from User u", Long.class).getSingleResult())
                    .isEqualTo(1L);
        }
    }

    @Test
    void secondMigrationPreservesExistingData() throws SQLException {
        Flyway flyway = migrate("schema_" + UUID.randomUUID().toString().replace("-", ""));
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(),
                DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement()) {
            connection.setSchema(flyway.getConfiguration().getDefaultSchema());
            statement.executeUpdate("insert into users(username,password,account_non_expired,account_non_locked,"
                    + "credentials_non_expired,enabled) values ('existing-reader','" + HASH + "',true,true,true,true)");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var rows = statement.executeQuery("select username from users")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("existing-reader");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void databaseEnforcesAccountAndRelationshipConstraints() throws SQLException {
        Flyway flyway = migrate("schema_" + UUID.randomUUID().toString().replace("-", ""));
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(),
                DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement()) {
            connection.setSchema(flyway.getConfiguration().getDefaultSchema());
            String insert = "insert into users(username,password,account_non_expired,account_non_locked,"
                    + "credentials_non_expired,enabled) values ('reader','" + HASH + "',true,true,true,true)";
            statement.executeUpdate(insert);
            assertThatThrownBy(() -> statement.executeUpdate(insert)).isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
            assertThatThrownBy(() -> statement.executeUpdate("insert into users(username) values ('missing-fields')"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23502");
            assertThatThrownBy(() -> statement.executeUpdate("insert into users(username,password) "
                    + "values (repeat('x',51),'" + HASH + "')"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("22001");
            assertThatThrownBy(() -> insertLegacy(connection, "users", "plain", "plaintext"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23514");
            assertThatThrownBy(() -> insertLegacy(connection, "admin", "plain", "{noop}plaintext"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23514");
            assertThatThrownBy(() -> statement.executeUpdate("insert into admin_actions values (999,999)"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23503");
        }
    }

    @Test
    void upgradesRecognizedLegacyHashesWithoutChangingAccountData() throws SQLException {
        String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        var v1 = flyway(schema, "1");
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = connection(schema)) {
            insertLegacy(connection, "users", "legacy-reader", HASH.substring(8));
            insertLegacy(connection, "admin", "legacy-admin", HASH.substring(8));
            var upgrade = flyway(schema, "2");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();
            for (String table : new String[]{"users", "admin"}) {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("select password, enabled, account_non_locked from " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(HASH);
                    assertThat(rows.getBoolean(2)).isFalse();
                    assertThat(rows.getBoolean(3)).isFalse();
                }
            }
        }
    }

    @Test
    void unsupportedLegacyValueRollsBackWholeUpgrade() throws SQLException {
        String schema = "rollback_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema, "1").migrate();
        try (var connection = connection(schema)) {
            insertLegacy(connection, "users", "recognized", HASH.substring(8));
            insertLegacy(connection, "admin", "unsupported", "legacy-plaintext-fixture");
            assertThatThrownBy(() -> flyway(schema, "2").migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
            try (var statement = connection.createStatement()) {
                for (String table : new String[]{"users", "admin"}) {
                    try (var rows = statement.executeQuery("select password from " + table)) {
                        rows.next();
                        assertThat(rows.getString(1)).isEqualTo(table.equals("users") ? HASH.substring(8) : "legacy-plaintext-fixture");
                    }
                }
                try (var rows = statement.executeQuery("select character_maximum_length from information_schema.columns "
                        + "where table_schema = '" + schema + "' and table_name='users' and column_name='password'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(60);
                }
                try (var rows = statement.executeQuery("select count(*) from flyway_schema_history where version='2'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void v3PreservesLegacyAccountsAndEnforcesNormalizedUniqueEmail() throws SQLException {
        String schema = "email_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema, "2").migrate();
        try (var connection = connection(schema)) {
            insertLegacy(connection, "users", "old-one", HASH);
            insertLegacy(connection, "users", "old-two", HASH);
            assertThat(flyway(schema, null).migrate().migrationsExecuted).isEqualTo(1);
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("select username,password,enabled,account_non_locked,email from users order by id")) {
                for (String name : new String[]{"old-one", "old-two"}) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(name);
                    assertThat(rows.getString(2)).isEqualTo(HASH);
                    assertThat(rows.getBoolean(3)).isFalse();
                    assertThat(rows.getBoolean(4)).isFalse();
                    assertThat(rows.getString(5)).isNull();
                }
                assertThat(rows.next()).isFalse();
            }
            try (var insert = connection.prepareStatement("insert into users(username,email,password) values (?,?,?)")) {
                insert.setString(1, "new-one"); insert.setString(2, "reader@example.test"); insert.setString(3, HASH);
                assertThat(insert.executeUpdate()).isEqualTo(1);
                insert.setString(1, "new-two");
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
                for (String invalid : new String[]{"READER@example.test", " reader@example.test ", ""}) {
                    insert.setString(2, invalid);
                    assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class)
                            .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23514");
                }
            }
        }
    }

    private static Connection connection(String schema) throws SQLException {
        var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        connection.setSchema(schema);
        return connection;
    }

    private static void insertLegacy(Connection connection, String table, String username, String hash) throws SQLException {
        // Table is a fixed test constant, values are always bound.
        try (var insert = connection.prepareStatement("insert into " + table
                + "(" + (table.equals("admin") ? "login" : "username") + ",password,account_non_expired,account_non_locked,credentials_non_expired,enabled)"
                + " values (?,?,true,false,true,false)")) {
            insert.setString(1, username);
            insert.setString(2, hash);
            insert.executeUpdate();
        }
    }

    private static Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .defaultSchema(schema).schemas(schema).cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Flyway migrate(String schema) {
        Flyway flyway = Flyway.configure().dataSource(DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(), DATABASE.getPassword())
                .defaultSchema(schema).schemas(schema).cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        flyway.validate();
        return flyway;
    }
}
