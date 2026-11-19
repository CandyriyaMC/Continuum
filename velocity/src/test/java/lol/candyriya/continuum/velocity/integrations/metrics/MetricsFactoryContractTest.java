package lol.candyriya.continuum.velocity.integrations.metrics;

import com.velocitypowered.api.proxy.ProxyServer;

import org.junit.jupiter.api.Test;

import org.slf4j.Logger;

import java.lang.reflect.Modifier;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Pins the reflection contract {@code VelocityMetricsService} relies on to build
 * the bStats exporter without Guice: the factory keeps its private
 * {@code (ProxyServer, Logger, Path)} constructor. If a bStats upgrade changes
 * this shape, this test fails loudly instead of the integration silently
 * degrading to "metrics disabled" in production.
 */
class MetricsFactoryContractTest {

    @Test
    void theFactoryConstructorHasTheDocumentedShapeAndIsReachable() {
        var factoryClass = org.bstats.velocity.Metrics.Factory.class;
        var ctor = assertDoesNotThrow(
                () -> factoryClass.getDeclaredConstructor(ProxyServer.class, Logger.class, Path.class),
                "bStats factory no longer has the (ProxyServer, Logger, Path) constructor");

        assertEquals(Modifier.PRIVATE, ctor.getModifiers() & Modifier.PRIVATE);
        assertDoesNotThrow(() -> ctor.setAccessible(true));
    }
}
