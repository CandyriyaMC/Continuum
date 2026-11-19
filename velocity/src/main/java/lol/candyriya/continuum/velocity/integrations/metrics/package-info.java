/**
 * Anonymous, opt-out bStats metrics (proxy side).
 *
 * <p>Inert until the bStats service id is set to a positive number at first
 * release, and opt-out via {@code enabled: false} in the node-local
 * {@code metrics.yml} or bStats' global config. No personal data is ever
 * collected — the custom charts report fixed configuration values only.</p>
 */
package lol.candyriya.continuum.velocity.integrations.metrics;
