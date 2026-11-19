package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.common.config.DatabaseType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MySQL 8.4 coverage. Skips cleanly when docker is unavailable - CI runs it,
 * a dockerless dev machine does not fail the build.
 */
class MysqlStorageIntegrationTest extends AbstractSqlStorageIntegrationTest {

    private static MySQLContainer container;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "docker is not available - skipping MySQL integration tests");
        container = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("continuum")
                .withUsername("continuum")
                .withPassword("continuum");
        container.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    protected DatabaseType engine() {
        return DatabaseType.MYSQL;
    }

    @Override
    protected TestDatabase connection() {
        return new TestDatabase(container.getHost(), container.getMappedPort(MySQLContainer.MYSQL_PORT),
                container.getDatabaseName(), container.getUsername(), container.getPassword());
    }
}
