/**
 * The player diagnostics aggregate — what Continuum knows about one player, without
 * triggering any synchronization.
 *
 * <p>{@link lol.candyriya.continuum.api.player.PlayerService} exposes a single
 * point-in-time view combining the live session and the newest stored snapshot, intended
 * for admin tooling, integrations and support diagnostics.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
package lol.candyriya.continuum.api.player;

import org.jetbrains.annotations.ApiStatus;
