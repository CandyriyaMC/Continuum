package lol.candyriya.continuum.storage.mongodb;

/**
 * Collection and counter-key names shared by the storage, the bootstrap and the tests.
 */
final class MongoCollections {

    static final String PLAYERS = "players";
    static final String PLAYER_SNAPSHOTS = "player_snapshots";
    static final String PLAYER_SNAPSHOT_HISTORY = "player_snapshot_history";
    static final String PLAYER_SESSIONS = "player_sessions";
    static final String PLAYER_STATS = "player_stats";
    static final String OUTBOX = "outbox";
    static final String SNAPSHOT_COUNTERS = "snapshot_counters";

    static final String COUNTER_FENCE = "fence";
    static final String COUNTER_HISTORY = "history";
    static final String COUNTER_SESSION = "session";
    static final String COUNTER_OUTBOX = "outbox";

    static final java.util.List<String> COUNTER_KEYS = java.util.List.of(
            COUNTER_FENCE, COUNTER_HISTORY, COUNTER_SESSION, COUNTER_OUTBOX);

    private MongoCollections() {
    }
}
