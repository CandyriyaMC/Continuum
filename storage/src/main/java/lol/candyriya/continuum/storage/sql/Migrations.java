package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.storage.api.SchemaMigrationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Discovery and checksums for classpath migrations ({@code db/migration/VNNN__name.sql}).
 *
 * <p>The list is explicit instead of directory-scanned: classpath directory
 * listing does not work from inside a jar, and this keeps "what migrations exist"
 * reviewable in one place. Adding a migration means adding the resource and one
 * line here — everything else (ordering, checksum, validation) is automatic.</p>
 */
final class Migrations {

    /** Every migration known to this build, in ascending order. */
    private static final List<String> KNOWN = List.of(
            "V001__initial_schema.sql"
    );

    private Migrations() {
    }

    /** One migration file, parsed and checksummed. */
    record Migration(long version, String name, String fileName, String checksum) {
    }

    /**
     * Loads and validates all known migrations.
     *
     * @throws SchemaMigrationException when a listed resource is missing, misnamed
     *                                  or two migrations share a version
     */
    static List<Migration> discover() {
        var migrations = new ArrayList<Migration>(KNOWN.size());
        for (String fileName : KNOWN) {
            migrations.add(load(fileName));
        }
        migrations.sort(Comparator.comparingLong(Migration::version));
        for (int i = 1; i < migrations.size(); i++) {
            if (migrations.get(i).version() == migrations.get(i - 1).version()) {
                throw new SchemaMigrationException(
                        "duplicate migration version " + migrations.get(i).version()
                                + " (" + migrations.get(i - 1).fileName() + " and " + migrations.get(i).fileName() + ")");
            }
        }
        return List.copyOf(migrations);
    }

    /**
     * Parses the version out of a migration file name: {@code V<digits>__<name>.sql}.
     *
     * @return the version, or empty when the name does not match
     */
    static Optional<Long> parseVersion(String fileName) {
        if (fileName == null || !fileName.endsWith(".sql")) {
            return Optional.empty();
        }
        int body = fileName.indexOf("__");
        if (body <= 1 || fileName.charAt(0) != 'V') {
            return Optional.empty();
        }
        String digits = fileName.substring(1, body);
        try {
            long version = Long.parseLong(digits);
            return version >= 1 ? Optional.of(version) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The human-readable migration name from the file name, or null when malformed. */
    static String parseName(String fileName) {
        if (fileName == null) {
            return null;
        }
        int body = fileName.indexOf("__");
        if (body < 0 || !fileName.endsWith(".sql")) {
            return null;
        }
        String name = fileName.substring(body + 2, fileName.length() - ".sql".length());
        return name.isBlank() ? null : name;
    }

    /** Loads one migration resource and checksums it. */
    static Migration load(String fileName) {
        var parsed = parseVersion(fileName);
        String name = parseName(fileName);
        if (parsed.isEmpty() || name == null) {
            throw new SchemaMigrationException("malformed migration file name: " + fileName);
        }
        byte[] bytes = readResource(fileName);
        return new Migration(parsed.get(), name, fileName, sha256Hex(bytes));
    }

    /** Reads the raw bytes of one migration resource. */
    static byte[] readResource(String fileName) {
        String path = "db/migration/" + fileName;
        try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new SchemaMigrationException("migration resource not found on the classpath: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new SchemaMigrationException("failed to read migration resource: " + path, e);
        }
    }

    /** Splits a migration into statements: strips {@code --} comments and blank lines, splits on {@code ;}. */
    static List<String> splitStatements(byte[] sql) {
        String text = new String(sql, StandardCharsets.UTF_8);
        var statements = new ArrayList<String>();
        var current = new StringBuilder();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine;
            int comment = line.indexOf("--");
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            current.append(line).append('\n');
            while (current.indexOf(";") >= 0) {
                int cut = current.indexOf(";");
                String statement = current.substring(0, cut).trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.delete(0, cut + 1);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return List.copyOf(statements);
    }

    /** Lowercase hex SHA-256 of {@code bytes}. */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JLS; a JVM without it is beyond broken
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
