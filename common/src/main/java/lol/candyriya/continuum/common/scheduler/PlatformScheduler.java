package lol.candyriya.continuum.common.scheduler;

/**
 * Platform-agnostic scheduling contract every Continuum component uses instead
 * of touching Paper/Folia/Velocity scheduler APIs directly.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li><b>Global</b> tasks run off any entity context — one shared lane per
 *       platform (Paper: global region thread; Folia: global region scheduler).
 *       Never touch a specific entity from these.</li>
 *   <li><b>Entity</b> tasks run on the owning thread/region of that entity —
 *       the only safe place to touch the entity's live state.</li>
 *   <li><b>Async</b> tasks run fully off the game loop (storage I/O, network,
 *       serialization). Never call platform entity/world APIs from here.</li>
 *   <li>No blocking I/O on game/region threads: DB, RabbitMQ, file sync — all
 *       belong in {@code async}.</li>
 *   <li>No guarantee that tasks observe a consistent world snapshot across
 *       calls; treat every run as a new observation.</li>
 * </ul>
 *
 * <p>Implementations are provided by the platform modules (Paper/Folia via
 * global+entity+region schedulers, Velocity via its async scheduler). A
 * complete, correct-for-tests implementation lives in this module as
 * {@link DirectScheduler}.</p>
 */
public interface PlatformScheduler {

    /** Runs {@code task} outside any entity context, as soon as the platform allows. */
    void global(Runnable task);

    /** Runs {@code task} on the owning thread/region of {@code entity}. */
    void entity(EntityRef entity, Runnable task);

    /** Runs {@code task} off the game loop entirely. */
    void async(Runnable task);
}
