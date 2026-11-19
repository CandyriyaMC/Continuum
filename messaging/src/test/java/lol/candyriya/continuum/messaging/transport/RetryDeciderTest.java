package lol.candyriya.continuum.messaging.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryDeciderTest {

    @Test
    void underBudgetIsRequeued() {
        for (int count = 1; count < 5; count++) {
            assertEquals(DeliveryDisposition.REQUEUE, RetryDecider.afterTransientFailure(count, 5));
        }
    }

    @Test
    void atBudgetIsDeadLettered() {
        assertEquals(DeliveryDisposition.DEAD_LETTER, RetryDecider.afterTransientFailure(5, 5));
        assertEquals(DeliveryDisposition.DEAD_LETTER, RetryDecider.afterTransientFailure(6, 5));
        assertEquals(DeliveryDisposition.DEAD_LETTER, RetryDecider.afterTransientFailure(100, 5));
    }

    @Test
    void budgetOfOneMeansStraightToTheDeadLetterExchange() {
        assertEquals(DeliveryDisposition.DEAD_LETTER, RetryDecider.afterTransientFailure(1, 1));
    }

    @Test
    void invalidBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RetryDecider.afterTransientFailure(1, 0));
        assertThrows(IllegalArgumentException.class, () -> RetryDecider.afterTransientFailure(1, -3));
    }

    @Test
    void brokerReportedCountWins() {
        assertEquals(1, RetryDecider.effectiveDeliveryCount(1, -1, false, 5));
        assertEquals(4, RetryDecider.effectiveDeliveryCount(4, -1, true, 5));
        assertEquals(0, RetryDecider.effectiveDeliveryCount(0, -1, true, 5));
        // acquired-count counts nacks; it alone is enough to advance the budget
        assertEquals(6, RetryDecider.effectiveDeliveryCount(-1, 6, true, 5));
        // when both exist, the bigger number wins
        assertEquals(7, RetryDecider.effectiveDeliveryCount(3, 7, true, 5));
        assertEquals(7, RetryDecider.effectiveDeliveryCount(7, 3, true, 5));
    }

    @Test
    void missingCountsFallBackToRedeliveredFlag() {
        // classic queue: no headers, redelivery known - burn the whole budget, we cannot tell
        assertEquals(5, RetryDecider.effectiveDeliveryCount(-1, -1, true, 5));
        // classic queue: first delivery
        assertEquals(1, RetryDecider.effectiveDeliveryCount(-1, -1, false, 5));
        // degenerate budget: redelivery still counts as at least one
        assertEquals(1, RetryDecider.effectiveDeliveryCount(-1, -1, true, 0));
    }
}
