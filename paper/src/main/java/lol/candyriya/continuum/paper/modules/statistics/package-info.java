/**
 * Synchronization of cumulative player statistics.
 *
 * <p>Statistics are monotonic counters, so this package never merges them as
 * plain state. Capture produces a full dump of the non-zero counter grid
 * under qualified keys ({@link lol.candyriya.continuum.paper.modules.statistics.StatKey});
 * restore reconciles it against the local counters with a catch-up-only rule
 * (the snapshot raises the local counter, never lowers it). See
 * {@link lol.candyriya.continuum.paper.modules.statistics.StatisticsModule}
 * for the complete contract, crash semantics and the known limitations.</p>
 */
package lol.candyriya.continuum.paper.modules.statistics;
