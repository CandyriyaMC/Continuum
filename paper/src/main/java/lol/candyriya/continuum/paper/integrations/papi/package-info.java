/**
 * Internal PlaceholderAPI expansion (no eCloud).
 *
 * <p>Detects PlaceholderAPI at runtime and registers a persistent, internal
 * expansion answering {@code %continuum_version%}, {@code %continuum_modules_enabled%},
 * {@code %continuum_role%} and {@code %continuum_restore_order%} — all derived from
 * the shared configuration or the plugin descriptor, all identical for every player,
 * never anything personal. Absent PlaceholderAPI: the integration logs and does
 * nothing, and the plugin enables exactly as usual.</p>
 */
package lol.candyriya.continuum.paper.integrations.papi;
