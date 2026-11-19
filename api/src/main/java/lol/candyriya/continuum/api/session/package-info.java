/**
 * The session model — the ownership half of Continuum's correctness story.
 *
 * <p>Exactly one backend owns a player's synchronized state at a time. Ownership is a
 * session: a record fenced by a monotonic token that the database checks inside its
 * atomic write. This package holds the state vocabulary
 * ({@link lol.candyriya.continuum.api.session.SessionState}), the immutable session view
 * ({@link lol.candyriya.continuum.api.session.SessionInfo}) and the read surface
 * ({@link lol.candyriya.continuum.api.session.SessionService}).</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
package lol.candyriya.continuum.api.session;

import org.jetbrains.annotations.ApiStatus;
