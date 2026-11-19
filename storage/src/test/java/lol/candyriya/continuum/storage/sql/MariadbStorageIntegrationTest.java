package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.common.config.DatabaseType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MariaDB 11 coverage. Skips cleanly when docker is unavailable - CI runs it,
 * a dockerless dev machine does not fail the build.
 */
class MariadbStorageIntegrationTest extends AbstractSqlStorageIntegrationTest {

    private static MariaDBContainer container;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "docker is not available - skipping MariaDB integration tests");
        container = new MariaDBContainer(DockerImageName.parse("mariadb:11"))
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
        return DatabaseType.MARIADB;
    }

    @Override
    protected TestDatabase connection() {
        return new TestDatabase(container.getHost(), container.getMappedPort(3306),
                container.getDatabaseName(), container.getUsername(), container.getPassword());
    }
}
