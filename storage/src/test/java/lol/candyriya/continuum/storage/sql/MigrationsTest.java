package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.storage.api.SchemaMigrationException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationsTest {

    // --- file name parsing ---------------------------------------------------

    @Test
    void parseVersionReadsTheDigits() {
        assertEquals(Optional.of(1L), Migrations.parseVersion("V001__initial_schema.sql"));
        assertEquals(Optional.of(10L), Migrations.parseVersion("V10__add_index.sql"));
        assertEquals(Optional.of(1234L), Migrations.parseVersion("V1234__many.sql"));
    }

    @Test
    void parseVersionRejectsMalformedNames() {
        assertTrue(Migrations.parseVersion(null).isEmpty());
        assertTrue(Migrations.parseVersion("").isEmpty());
        assertTrue(Migrations.parseVersion("001__foo.sql").isEmpty());     // no V prefix
        assertTrue(Migrations.parseVersion("v001__foo.sql").isEmpty());     // wrong case
        assertTrue(Migrations.parseVersion("V__foo.sql").isEmpty());        // no digits
        assertTrue(Migrations.parseVersion("V1.5__foo.sql").isEmpty());     // not a number
        assertTrue(Migrations.parseVersion("V001__foo.txt").isEmpty());     // not .sql
        assertTrue(Migrations.parseVersion("V0__zero.sql").isEmpty());      // versions are 1-based
    }

    @Test
    void parseNameReadsTheHumanReadablePart() {
        assertEquals("initial_schema", Migrations.parseName("V001__initial_schema.sql"));
        assertEquals("add_index", Migrations.parseName("V2__add_index.sql"));
    }

    @Test
    void parseNameRejectsMalformedNames() {
        assertEquals(null, Migrations.parseName(null));
        assertEquals(null, Migrations.parseName("V001__.sql"));   // blank name
        assertEquals(null, Migrations.parseName("no_marker.sql")); // no __ separator
    }

    // --- checksum ------------------------------------------------------------

    @Test
    void sha256MatchesTheKnownVector() {
        // well-known SHA-256 of "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Migrations.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sha256OfEmptyInputMatchesTheVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Migrations.sha256Hex(new byte[0]));
    }

    // --- statement splitting --------------------------------------------------

    @Test
    void splitStatementsSeparatesOnSemicolons() {
        byte[] sql = "CREATE TABLE a (id INT);\nINSERT INTO a VALUES (1);\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of("CREATE TABLE a (id INT)", "INSERT INTO a VALUES (1)"),
                Migrations.splitStatements(sql));
    }

    @Test
    void splitStatementsStripsCommentOnlyLinesAndTrailingComments() {
        byte[] sql = ("-- leading comment\n"
                + "CREATE TABLE a (id INT); -- trailing comment\n"
                + "-- another comment\n"
                + "INSERT INTO a VALUES (1);\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of("CREATE TABLE a (id INT)", "INSERT INTO a VALUES (1)"),
                Migrations.splitStatements(sql));
    }

    @Test
    void splitStatementsHandlesEmptyAndCommentOnlyInput() {
        assertTrue(Migrations.splitStatements(new byte[0]).isEmpty());
        assertTrue(Migrations.splitStatements("-- nothing else\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(Migrations.splitStatements(";; ;\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void splitStatementsKeepsMultiLineStatements() {
        byte[] sql = ("CREATE TABLE a (\n    id INT NOT NULL,\n    val VARCHAR(10)\n);\n")
                .getBytes(StandardCharsets.UTF_8);
        List<String> statements = Migrations.splitStatements(sql);
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).contains("val VARCHAR(10)"));
    }

    // --- discovery -----------------------------------------------------------

    @Test
    void discoverFindsTheBundledMigration() {
        List<Migrations.Migration> migrations = Migrations.discover();
        assertFalse(migrations.isEmpty());
        Migrations.Migration first = migrations.get(0);
        assertEquals(1L, first.version());
        assertEquals("initial_schema", first.name());
        assertEquals(64, first.checksum().length());
        assertTrue(first.checksum().matches("[0-9a-f]{64}"));
    }

    // --- boot-time validation --------------------------------------------------

    private static List<Migrations.Migration> known() {
        return Migrations.discover();
    }

    private static MigrationRunner.AppliedMigration appliedRow(long version, String name, String checksum) {
        return new MigrationRunner.AppliedMigration(version, name, checksum);
    }

    @Test
    void validateAcceptsMatchingHistory() {
        Migrations.Migration v1 = known().get(0);
        Map<Long, MigrationRunner.AppliedMigration> applied = Map.of(
                1L, appliedRow(1L, v1.name(), v1.checksum()));
        MigrationRunner.validate(known(), applied); // must not throw
    }

    @Test
    void validateAcceptsEmptyHistory() {
        MigrationRunner.validate(known(), Map.of()); // fresh database, everything pending
    }

    @Test
    void validateRejectsAppliedVersionUnknownToThisBuild() {
        Migrations.Migration v1 = known().get(0);
        Map<Long, MigrationRunner.AppliedMigration> applied = Map.of(
                1L, appliedRow(1L, v1.name(), v1.checksum()),
                999L, appliedRow(999L, "from_the_future", "ab".repeat(32)));
        assertThrows(SchemaMigrationException.class, () -> MigrationRunner.validate(known(), applied));
    }

    @Test
    void validateRejectsChecksumMismatch() {
        Migrations.Migration v1 = known().get(0);
        Map<Long, MigrationRunner.AppliedMigration> applied = Map.of(
                1L, appliedRow(1L, v1.name(), "ff".repeat(32)));
        assertThrows(SchemaMigrationException.class, () -> MigrationRunner.validate(known(), applied));
    }

    @Test
    void validateRejectsNameMismatch() {
        Migrations.Migration v1 = known().get(0);
        Map<Long, MigrationRunner.AppliedMigration> applied = Map.of(
                1L, appliedRow(1L, "renamed", v1.checksum()));
        assertThrows(SchemaMigrationException.class, () -> MigrationRunner.validate(known(), applied));
    }

    @Test
    void validateChecksumComparisonIsCaseInsensitive() {
        Migrations.Migration v1 = known().get(0);
        Map<Long, MigrationRunner.AppliedMigration> applied = Map.of(
                1L, appliedRow(1L, v1.name(), v1.checksum().toUpperCase()));
        MigrationRunner.validate(known(), applied); // uppercase hex is accepted
    }
}
