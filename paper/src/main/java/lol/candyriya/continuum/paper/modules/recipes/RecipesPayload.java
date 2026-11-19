package lol.candyriya.continuum.paper.modules.recipes;

import java.util.List;

/**
 * Wire payload for {@code continuum.recipes} (schema {@link #FORMAT}).
 *
 * <p>Serialized by the snapshot layer as JSON through the shared Continuum
 * mapper (snake_case keys). Component names are part of the stable wire
 * contract — renaming one is a schema bump. Example payload:</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "recipes": ["minecraft:crafting_table", "my_mod:copper_gear"]
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code recipes} holds the discovered recipe book of the player as
 *       raw {@code namespace:path} strings, sorted lexicographically and
 *       duplicate-free. Unknown namespaces (datapacks, modded servers) are
 *       preserved verbatim — the target decides at restore time whether it
 *       knows each key.</li>
 *   <li>Restore mirrors the captured set exactly: recipes in the set are
 *       discovered, discovered recipes absent from the set are forgotten.
 *       See {@code RecipesModule} for the semantics contract.</li>
 *   <li>Capture rejects an irreducibly broken set (invalid keys or a
 *       discovered-book larger than {@link #MAX_RECIPES}) by throwing, never
 *       by silently truncating — the snapshot never holds half the truth.</li>
 * </ul>
 *
 * <p>This record is deliberately a dumb DTO: structural checks live in
 * {@link RecipesValidation} so a corrupt stored payload surfaces as a
 * validation failure with a readable reason instead of an opaque decode
 * error. Stored payloads older than {@value #FORMAT} are not migrated —
 * there is exactly one schema, anything else is rejected by validation.</p>
 *
 * @param format  payload format version, always {@value #FORMAT}
 * @param recipes discovered recipe keys as {@code namespace:path} strings, may be empty
 */
public record RecipesPayload(int format, List<String> recipes) {

    /** Current wire format. Bump when the serialized shape changes incompatibly. */
    public static final int FORMAT = 1;

    /**
     * Hard cap on the number of recipe keys a payload may carry. Vanilla ships
     * a few hundred recipes and heavily modded packs climb into the low
     * thousands; {@value #MAX_RECIPES} leaves generous headroom while still
     * bouncing a pathological or hand-edited snapshot before it does damage.
     * Enforced by capture (as a failure) and by validation (as a rejection).
     */
    public static final int MAX_RECIPES = 5000;
}
