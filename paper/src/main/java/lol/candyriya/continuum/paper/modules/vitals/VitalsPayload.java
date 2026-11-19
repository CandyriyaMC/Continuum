package lol.candyriya.continuum.paper.modules.vitals;

/**
 * Wire payload for {@code continuum.vitals} (schema {@link #FORMAT}).
 *
 * <p>Serialized by the snapshot layer as JSON through the shared Continuum
 * mapper (snake_case keys, enums as names — see the snapshot docs). Component
 * names are part of the stable wire contract; renaming one is a schema bump,
 * never a silent refactor. Example payload ({@code fall_distance} present):</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "health": 17.5,
 *   "max_health_attribute": "minecraft:max_health",
 *   "max_health_base": 20.0,
 *   "absorption": 4.0,
 *   "food_level": 17,
 *   "saturation": 9.5,
 *   "exhaustion": 2.5,
 *   "remaining_air": 187,
 *   "fire_ticks": 0,
 *   "freeze_ticks": 0,
 *   "fall_distance": 1.75
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code max_health_attribute} / {@code max_health_base} are diagnostic
 *       metadata: they record which attribute fed the health ceiling and its
 *       base value at capture time. The vitals module never writes max health —
 *       restoring {@code minecraft:max_health} is the attributes module's job,
 *       and the restore plan runs it before this module. The base is kept so a
 *       snapshot can be inspected without unpacking the attributes payload.</li>
 *   <li>{@code fall_distance} is nullable; {@code null} means the fall distance
 *       was not captured (the module can be constructed with
 *       {@code captureFallDistance = false} for transports where an in-flight
 *       fall is meaningless).</li>
 *   <li>{@code remaining_air} is a raw tick count. There is deliberately no
 *       max-air field: Bukkit exposes no max-air API, and the max is a function
 *       of attributes (e.g. {@code minecraft:oxygen_bonus}) applied at the
 *       server, not of this entity instance. Restoring the raw count is enough.</li>
 *   <li>{@code exhaustion} is passed through unclamped above 0; vanilla keeps it
 *       below 4 by draining, but mods may run wider scales and clamping here
 *       would corrupt them.</li>
 * </ul>
 *
 * @param format              payload format version, always {@value #FORMAT}
 * @param health              current health, 0..effective max (clamped at restore)
 * @param maxHealthAttribute  namespaced key of the max-health attribute ({@value #MAX_HEALTH_ATTRIBUTE_KEY})
 * @param maxHealthBase       base value of the max-health attribute at capture time; diagnostic only
 * @param absorption          absorption hearts, &gt;= 0
 * @param foodLevel           food level, 0..20
 * @param saturation          saturation, &gt;= 0 (may exceed food level in a captured snapshot; clamped at restore)
 * @param exhaustion          exhaustion, &gt;= 0
 * @param remainingAir        ticks of air remaining, &gt;= 0
 * @param fireTicks           fire ticks remaining, &gt;= 0
 * @param freezeTicks         freeze ticks accumulated, &gt;= 0
 * @param fallDistance        fall distance in blocks, or null when not captured
 */
public record VitalsPayload(
        int format,
        double health,
        String maxHealthAttribute,
        double maxHealthBase,
        double absorption,
        int foodLevel,
        float saturation,
        float exhaustion,
        int remainingAir,
        int fireTicks,
        int freezeTicks,
        Float fallDistance) {

    /** Current wire format. Bump when the serialized shape changes incompatibly. */
    public static final int FORMAT = 1;

    /** Namespaced key of the attribute that backs max health. */
    public static final String MAX_HEALTH_ATTRIBUTE_KEY = "minecraft:max_health";
}
