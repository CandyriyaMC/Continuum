package lol.candyriya.continuum.storage.mongodb;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MongoDB 8 coverage against a single-node replica set (transactions require
 * one). Skips cleanly when docker is unavailable — CI runs it, a dockerless
 * dev machine does not fail the build.
 *
 * <p>A pre-provisioned replica set can be supplied instead of docker via the
 * {@code continuum.test.mongodb.uri} system property or the
 * {@code CONTINUUM_TEST_MONGODB_URI} environment variable (a replica set URI,
 * e.g. one from a CI service container):
 * {@code CONTINUUM_TEST_MONGODB_URI="mongodb://localhost:27099/?replicaSet=rs0" ./gradlew :storage:test}.
 * When either is set, docker is not required and the suite runs against the
 * given deployment; every test uses fresh player UUIDs and drains the outbox,
 * so a shared database is tolerated.</p>
 */
class MongoStorageIntegrationTest extends AbstractMongoStorageIntegrationTest {

    private static final String EXTERNAL_URI = System.getProperty("continuum.test.mongodb.uri",
            System.getenv("CONTINUUM_TEST_MONGODB_URI"));

    private static MongoDBContainer container;

    @BeforeAll
    static void startDatabase() {
        if (EXTERNAL_URI != null && !EXTERNAL_URI.isBlank()) {
            // an external replica set was supplied; docker stays out of it
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "docker is not available - skipping MongoDB integration tests");
        container = new MongoDBContainer(DockerImageName.parse("mongo:8.0"))
                .withReplicaSet();
        container.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    protected String uri() {
        if (EXTERNAL_URI != null && !EXTERNAL_URI.isBlank()) {
            return EXTERNAL_URI;
        }
        return container.getReplicaSetUrl();
    }

    @Override
    protected String database() {
        return "continuum";
    }
}
