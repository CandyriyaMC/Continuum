package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.storage.api.PlayerSnapshot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shared in-memory "last known good" bookmark.
 *
 * <p>One instance, shared by the capture and restore pipelines: a successful
 * save or an applied restore refreshes it; a lost write (stale token, storage
 * failure) drops it. It exists so an autosave can carry unchanged modules
 * forward instead of re-capturing them — storage stores a whole envelope per
 * player, so every write always carries the merge.</p>
 */
final class LastKnownGoodStore {

    private final ConcurrentHashMap<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();

    PlayerSnapshot get(UUID playerId) {
        return snapshots.get(playerId);
    }

    void put(UUID playerId, PlayerSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshots.put(playerId, snapshot);
    }

    void remove(UUID playerId) {
        snapshots.remove(playerId);
    }

    int size() {
        return snapshots.size();
    }
}
