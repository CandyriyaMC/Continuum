package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Connection settings for MySQL and MariaDB (shared block, see
 * {@link DatabaseType}). Secrets stay plain strings by design — how they are
 * stored is the operator's business (file permissions, env templating); they
 * are never logged and never echoed in error messages.
 *
 * @param host     database host or IP
 * @param port     JDBC port, 1-65535
 * @param database catalog/schema to use
 * @param username login name
 * @param password login password; empty means "no auth" (local dev), never logged
 * @param pool     HikariCP pool tuning
 */
@ConfigSerializable
public record MysqlSettings(String host, int port, String database, String username, String password, MysqlPool pool) {

    @Override
    public String toString() {
        // mask the password: configs get dumped into support tickets all the time
        return "MysqlSettings[host=%s, port=%d, database=%s, username=%s, password=%s, pool=%s]"
                .formatted(host, port, database, username, password == null || password.isEmpty() ? "<empty>" : "<masked>", pool);
    }
}
