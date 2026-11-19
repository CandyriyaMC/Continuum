package lol.candyriya.continuum.messaging.outbox;

/** Point-in-time relay counters. */
public record OutboxRelayStats(long claimed, long published, long failed, long claimFailures) {
}
