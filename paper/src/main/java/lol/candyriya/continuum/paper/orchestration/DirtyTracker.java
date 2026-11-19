package lol.candyriya.continuum.paper.orchestration;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-module dirty flags — the optimization layer over full captures.
 *
 * <p>Tracks which modules changed since the last successful full capture (quit,
 * transfer, shutdown, manual) or the last successful autosave. The tracker is
 * deliberately dumb: it does not know what is enabled, only which ids were
 * marked. Callers advance the state machine:</p>
 * <ul>
 *   <li>platform event listeners call {@link #markDirty(UUID, String)} as game
 *       state changes happen;</li>
 *   <li>a successful capture calls {@link #clearDirty(UUID)} (only the modules
 *       it persisted are cleared by the caller through {@link #clear(UUID, Collection)});</li>
 *   <li>a rejected/lost write marks everything dirty again with
 *       {@link #markAllDirty(UUID, Collection)} so the next capture is full.</li>
 * </ul>
 *
 * <p>Dirty tracking is an optimization: skipping a capture when nothing is
 * dirty never affects correctness, and losing a flag only costs an extra
 * capture.</p>
 */
public final class DirtyTracker {

    private final ConcurrentHashMap<UUID, Set<String>> dirty = new ConcurrentHashMap<>();

    /** Whether the module changed since the last clear for this player. */
    public boolean isDirty(UUID playerId, String moduleId) {
        Set<String> modules = dirty.get(Objects.requireNonNull(playerId, "playerId"));
        return modules != null && modules.contains(Objects.requireNonNull(moduleId, "moduleId"));
    }

    /** Any change recorded for this player. */
    public boolean isAnyDirty(UUID playerId) {
        Set<String> modules = dirty.get(Objects.requireNonNull(playerId, "playerId"));
        return modules != null && !modules.isEmpty();
    }

    /** Marks one module dirty. Cheap; callable from event handlers. */
    public void markDirty(UUID playerId, String moduleId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(moduleId, "moduleId");
        if (moduleId.isBlank()) {
            return;
        }
        dirty.computeIfAbsent(playerId, id -> concurrentSet()).add(moduleId);
    }

    /** Marks every module in {@code moduleIds} dirty — used after a lost write. */
    public void markAllDirty(UUID playerId, Collection<String> moduleIds) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(moduleIds, "moduleIds");
        if (moduleIds.isEmpty()) {
            return;
        }
        dirty.computeIfAbsent(playerId, id -> concurrentSet()).addAll(moduleIds);
    }

    /**
     * Clears the given modules for the player. Modules not named stay dirty —
     * this is how a capture of only a subset (autosave) preserves the flags of
     * the modules it did not persist.
     */
    public void clear(UUID playerId, Collection<String> moduleIds) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(moduleIds, "moduleIds");
        Set<String> modules = dirty.get(playerId);
        if (modules == null) {
            return;
        }
        synchronized (modules) {
            modules.removeAll(moduleIds);
            if (modules.isEmpty()) {
                dirty.remove(playerId, modules);
            }
        }
    }

    /** Clears everything for the player — after a full capture or a restore. */
    public void clearAll(UUID playerId) {
        dirty.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    /** Snapshot of the dirty set; never null, may be empty, unmodifiable. */
    public Set<String> dirtyModules(UUID playerId) {
        Set<String> modules = dirty.get(Objects.requireNonNull(playerId, "playerId"));
        if (modules == null) {
            return Set.of();
        }
        synchronized (modules) {
            return Collections.unmodifiableSet(new HashSet<>(modules));
        }
    }

    /** Players with at least one dirty module; for diagnostics. */
    public Set<UUID> trackedPlayers() {
        return Set.copyOf(dirty.keySet());
    }

    public int size() {
        return dirty.size();
    }

    private static Set<String> concurrentSet() {
        return Collections.synchronizedSet(new HashSet<>());
    }
}
