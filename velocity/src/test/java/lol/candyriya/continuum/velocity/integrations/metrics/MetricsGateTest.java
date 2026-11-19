package lol.candyriya.continuum.velocity.integrations.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsGateTest {

    @Test
    void optedInWithoutAServiceIdStaysFullyOffline() {
        // the whole point of the gate: enabled by config + unregistered id must never reach the network
        assertEquals(MetricsGate.NOT_REGISTERED, MetricsGate.decide(true, 0));
    }

    @Test
    void optedInWithAServiceIdStarts() {
        assertEquals(MetricsGate.ENABLED, MetricsGate.decide(true, 12345));
    }

    @Test
    void optedOutStaysOffEvenWithAServiceId() {
        assertEquals(MetricsGate.DISABLED_BY_CONFIG, MetricsGate.decide(false, 12345));
    }

    @Test
    void optedOutAndUnregisteredIsReportedAsTheOptOut() {
        assertEquals(MetricsGate.DISABLED_BY_CONFIG, MetricsGate.decide(false, 0));
    }

    @Test
    void aBogusNegativeIdNeverEnables() {
        assertEquals(MetricsGate.NOT_REGISTERED, MetricsGate.decide(true, -1));
    }
}
