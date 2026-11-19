package lol.candyriya.continuum.common.config;

/**
 * Which database engine backs the shared state.
 *
 * <p>{@code MYSQL} and {@code MARIADB} share the same settings block
 * ({@code database.mysql.*}) — the engines are wire-compatible enough for
 * HikariCP and the same JDBC SQL; only the driver and the dialect quirks
 * differ. {@code MONGODB} uses {@code database.mongodb.*} instead.</p>
 */
public enum DatabaseType {

    MYSQL,
    MARIADB,
    MONGODB
}
