package lol.candyriya.continuum.paper.modules.location;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Read-side access to the worlds loaded on this node, isolated so the restore
 * planner can be unit-tested without a server.
 *
 * <p>Both methods are called on the player's entity thread (during restore). Implementations
 * must be cheap, non-blocking and must never force-load a world or a chunk — the answer for
 * a world that is not loaded is "no", never a load.</p>
 */
public interface WorldLookup {

    /** True when a world with this exact name is loaded on this node. The name is never normalized. */
    boolean exists(@NotNull String worldName);

    /**
     * The dimension family of the world ({@code World#getEnvironment()}'s name, e.g. {@code NORMAL},
     * {@code NETHER}, {@code THE_END}, {@code CUSTOM}), or {@code null} when the world is not
     * loaded or its environment cannot be read. Null means "no opinion" — the caller must not
     * treat it as a mismatch.
     */
    @Nullable String environmentOf(@NotNull String worldName);
}
