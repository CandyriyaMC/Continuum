package lol.candyriya.continuum.messaging.transport;

/**
 * Immutable point-in-time view of a transport's counters. Read with
 * {@code stats.snapshot()}; all values are monotonic since transport creation.
 */
public record TransportStatsSnapshot(long published,
                                     long confirmed,
                                     long failed,
                                     long returned,
                                     long redelivered,
                                     long deadLettered,
                                     long malformed,
                                     long unknownType,
                                     long duplicatesSkipped,
                                     long recoveries) {
}
