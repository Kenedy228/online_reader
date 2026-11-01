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
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in real PostgreSQL contract checks; never uses a developer datasource. */
@Testcontainers
class PostgresMigrationsIT {
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
            session.persist(new User("reader", "synthetic-hash"));
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
                    + "credentials_non_expired,enabled) values ('existing-reader','synthetic-hash',true,true,true,true)");
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
                    + "credentials_non_expired,enabled) values ('reader','synthetic-hash',true,true,true,true)";
            statement.executeUpdate(insert);
            assertThatThrownBy(() -> statement.executeUpdate(insert)).isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
            assertThatThrownBy(() -> statement.executeUpdate("insert into users(username) values ('missing-fields')"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23502");
            assertThatThrownBy(() -> statement.executeUpdate("insert into users(username,password) "
                    + "values (repeat('x',51),'synthetic-hash')"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("22001");
            assertThatThrownBy(() -> statement.executeUpdate("insert into admin_actions values (999,999)"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23503");
        }
    }

    private static Flyway migrate(String schema) {
        Flyway flyway = Flyway.configure().dataSource(DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(), DATABASE.getPassword())
                .defaultSchema(schema).schemas(schema).cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        return flyway;
    }
}
