package lol.candyriya.continuum.paper.modules.experience;

/**
 * Wire payload for {@code continuum.experience} (schema {@link #FORMAT}).
 *
 * <p>Serialized by the snapshot layer as JSON through the shared Continuum
 * mapper (snake_case keys, enums as names). Component names are part of the
 * stable wire contract — renaming one is a schema bump. Example payload:</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "level": 30,
 *   "progress": 0.42,
 *   "total_experience": 1234
 * }
 * }</pre>
 *
 * <p>All three fields are restored verbatim. Partial progress inside a level
 * is only recoverable because {@code progress} travels with {@code level} —
 * storing the level alone would silently round every player down to a full
 * level. {@code total_experience} is independent server-side state (the
 * client never displays it); it travels too, since mending and
 * {@code /experience} screens read it.</p>
 *
 * <p>The module never recomputes one field from another: vanilla and Paper
 * keep the three as separate fields, so the stored triple is applied exactly
 * as captured instead of being derived (a derive step would discard
 * progress whenever the stored triple disagrees — which happens after
 * /xp, gamemode switches and ore-block boosts).</p>
 *
 * @param format          payload format version, always {@value #FORMAT}
 * @param level           experience level, &gt;= 0
 * @param progress        progress towards the next level, 0.0..1.0 (inclusive; 1.0 is a pending level-up)
 * @param totalExperience total experience points ever collected, &gt;= 0
 */
public record ExperiencePayload(
        int format,
        int level,
        float progress,
        int totalExperience) {

    /** Current wire format. Bump when the serialized shape changes incompatibly. */
    public static final int FORMAT = 1;
}
