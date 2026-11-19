package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.storage.api.SchemaMigrationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Flyway-in-miniature: validates {@code schema_migrations} against the
 * classpath and applies what is missing. No external migration framework —
 * full control, no hidden behavior, and every failure is fatal to storage.
 *
 * <p>Validation runs before anything is applied: every applied row must have a
 * matching migration in this build with the same name and SHA-256 checksum,
 * and every migration in this build must either be applied already or pending.
 * A mismatch (edited migration, unknown applied version) refuses to boot —
 * half-migrated schemas are never served.</p>
 *
 * <p>DDL in MySQL/MariaDB is not transactional (implicit commit), so migrations
 * must be idempotent. Pending migrations are applied in version order; a
 * failure aborts startup and the recording row is not written, so the next
 * boot retries cleanly.</p>
 */
final class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private static final String CREATE_MIGRATIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version      BIGINT       NOT NULL,
                name         VARCHAR(255) NOT NULL,
                checksum     CHAR(64)     NOT NULL,
                execution_ms BIGINT       NOT NULL,
                applied_at   DATETIME(6)  NOT NULL,
                PRIMARY KEY (version)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private MigrationRunner() {
    }

    /**
     * Validates and applies all pending migrations.
     *
     * @throws SchemaMigrationException when validation fails or a migration cannot be applied
     */
    static void run(DataSource dataSource) throws SchemaMigrationException {
        List<Migrations.Migration> known = Migrations.discover();
        try (Connection connection = dataSource.getConnection()) {
            ensureMigrationsTable(connection);
            Map<Long, AppliedMigration> applied = readApplied(connection);
            validate(known, applied);
            for (Migrations.Migration migration : known) {
                if (applied.containsKey(migration.version())) {
                    continue;
                }
                apply(connection, migration);
            }
        } catch (SQLException e) {
            throw new SchemaMigrationException("failed to run schema migrations: " + e.getMessage(), e);
        }
    }

    /** One row of schema_migrations as read back from the database. */
    record AppliedMigration(long version, String name, String checksum) {
    }

    private static void ensureMigrationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_MIGRATIONS_TABLE);
        }
    }

    private static Map<Long, AppliedMigration> readApplied(Connection connection) throws SQLException {
        Map<Long, AppliedMigration> applied = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version, name, checksum FROM schema_migrations ORDER BY version")) {
            while (rs.next()) {
                applied.put(rs.getLong(1), new AppliedMigration(rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        }
        return applied;
    }

    static void validate(List<Migrations.Migration> known, Map<Long, AppliedMigration> applied) throws SchemaMigrationException {
        Map<Long, Migrations.Migration> byVersion = new HashMap<>();
        for (Migrations.Migration migration : known) {
            byVersion.put(migration.version(), migration);
        }
        // anything applied that this build does not know about means a downgrade
        for (AppliedMigration row : applied.values()) {
            if (!byVersion.containsKey(row.version())) {
                throw new SchemaMigrationException(
                        "schema_migrations contains version " + row.version() + " (" + row.name()
                                + ") which is not part of this build — refusing to start");
            }
        }
        // anything known that was applied must match name + checksum
        for (Migrations.Migration migration : known) {
            AppliedMigration row = applied.get(migration.version());
            if (row == null) {
                continue;
            }
            if (!row.name().equals(migration.name())) {
                throw new SchemaMigrationException(
                        "migration V" + migration.version() + " was applied as '" + row.name()
                                + "' but this build ships '" + migration.name() + "' — refusing to start");
            }
            if (!row.checksum().equalsIgnoreCase(migration.checksum())) {
                throw new SchemaMigrationException(
                        "migration V" + migration.version() + " (" + migration.name()
                                + ") was applied with checksum " + row.checksum()
                                + " but this build ships " + migration.checksum()
                                + " — the migration file was edited after being applied; refusing to start");
            }
        }
    }

    private static void apply(Connection connection, Migrations.Migration migration) throws SQLException, SchemaMigrationException {
        byte[] bytes = Migrations.readResource(migration.fileName());
        List<String> statements = Migrations.splitStatements(bytes);
        log.info("Applying migration V{} ({}): {} statement(s)", migration.version(), migration.name(), statements.size());

        long started = System.nanoTime();
        // Wrap in a transaction: DML rides the same commit as the record row.
        // DDL still auto-commits (MySQL/MariaDB implicit commit), so migrations must be
        // idempotent — a half-applied migration re-runs cleanly at next boot.
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            long executionMs = (System.nanoTime() - started) / 1_000_000;
            recordApplied(connection, migration, executionMs);
            connection.commit();
            log.info("Applied migration V{} ({}) in {} ms", migration.version(), migration.name(), executionMs);
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new SchemaMigrationException(
                    "migration V" + migration.version() + " (" + migration.name() + ") failed: " + e.getMessage()
                            + " — storage is disabled until this is fixed; fix the schema or restore from backup", e);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void recordApplied(Connection connection, Migrations.Migration migration, long executionMs)
            throws SQLException {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migrations (version, name, checksum, execution_ms, applied_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setLong(1, migration.version());
            statement.setString(2, migration.name());
            statement.setString(3, migration.checksum());
            statement.setLong(4, executionMs);
            statement.setObject(5, now);
            statement.executeUpdate();
        }
    }
}
