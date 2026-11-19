/**
 * Syncs player advancement progress across backends: awarded criteria, completion
 * state, and (as far as the platform reports it) award timestamps.
 *
 * <p>Capture iterates the server's advancement registry and records every advancement
 * the player has touched — including recipe-unlock advancements, which vanilla also
 * grants. Restore is award-only and per entry: an advancement the target server does
 * not know is skipped with a warning, never fatal, and criteria revocation is never
 * performed. Completion converges through criteria, not through any direct write.</p>
 *
 * <p><b>Thread affinity.</b> All entry points run on the thread that owns the player
 * (the server main thread on Paper, the entity's region thread on Folia), like every
 * other module. Registry iteration and progress reads/writes are only legal there;
 * the module never hops threads itself.</p>
 */
package lol.candyriya.continuum.paper.modules.advancements;
