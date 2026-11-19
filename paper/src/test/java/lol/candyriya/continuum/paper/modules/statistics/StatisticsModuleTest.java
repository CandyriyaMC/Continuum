package lol.candyriya.continuum.paper.modules.statistics;

import lol.candyriya.continuum.api.module.BuiltinModules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Module identity contract — the parity rules every builtin module plays by.
 *
 * <p>Note: the {@code StatisticsModule} class itself is not loaded here on
 * purpose. The test runtime classpath carries no platform API (module wiring is
 * deliberately pure string/enum logic), and linking the module class eagerly
 * resolves the platform hierarchy — the live wiring is exercised when the
 * module is registered in a real server, not in a unit test.</p>
 */
class StatisticsModuleTest {

    @Test
    void builtinIdIsStable() {
        assertEquals("continuum.statistics", BuiltinModules.STATISTICS);
    }

    @Test
    void payloadFormatIsStable() {
        assertEquals(1, StatisticsPayload.FORMAT);
    }

    @Test
    void payloadCopiesTheStatsListDefensively() {
        List<StatisticsPayload.Entry> mutable = new ArrayList<>();
        mutable.add(new StatisticsPayload.Entry("untyped:deaths", 1L));

        StatisticsPayload payload = new StatisticsPayload(StatisticsPayload.FORMAT, mutable);
        mutable.clear();

        assertEquals(1, payload.stats().size());
        assertThrows(UnsupportedOperationException.class,
                () -> payload.stats().add(new StatisticsPayload.Entry("untyped:deaths", 2L)));
    }

    @Test
    void payloadToleratesNullStatsList() {
        StatisticsPayload payload = new StatisticsPayload(StatisticsPayload.FORMAT, null);

        assertEquals(List.of(), payload.stats());
    }
}
