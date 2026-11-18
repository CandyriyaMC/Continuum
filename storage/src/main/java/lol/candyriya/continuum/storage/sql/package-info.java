/**
 * JDBC-based storage (MySQL / MariaDB) backed by a single HikariCP pool per configuration.
 * Prepared statements only; transactions and fencing checks live inside the atomic operations.
 */
package lol.candyriya.continuum.storage.sql;
