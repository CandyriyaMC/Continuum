package lol.candyriya.continuum.paper.modules.metadata;

import org.jetbrains.annotations.Nullable;

/**
 * Wire payload for {@code continuum.metadata} (schema {@link #FORMAT}).
 *
 * <p>Serialized by the snapshot layer as JSON through the shared Continuum
 * mapper (snake_case keys). Example of a fully-customized capture:</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "display_name": "{\"text\":\"[VIP] Steve\",\"color\":\"gold\"}",
 *   "player_list_name": "{\"text\":\"Stev\",\"color\":\"yellow\"}"
 * }
 * }</pre>
 *
 * <p>A player with no custom names captured produces:</p>
 *
 * <pre>{@code
 * { "format": 1, "display_name": null, "player_list_name": null }
 * }</pre>
 *
 * <ul>
 *   <li>{@code display_name} and {@code player_list_name} hold <b>adventure gson
 *       component JSON</b> ({@code GsonComponentSerializer}), never MiniMessage —
 *       gson JSON is the format the platform itself ships for component payloads
 *       and round-trips arbitrary styled components exactly; MiniMessage does not
 *       (user-typed MiniMessage markup would not survive parse → serialize →
 *       parse, and would reinterpret escapes).</li>
 *   <li>{@code null} means "the source had no custom value"; restore applies it
 *       as an unconditional reset to the vanilla default
 *       ({@code displayName(null)} / {@code playerListName(null)}), mirroring
 *       the exact state the player left. Explicit {@code null} is meaningful on
 *       the wire and must not be stripped by consumers.</li>
 *   <li>A captured component equal to the plain player name
 *       ({@code Component.text(name)}) is normalized to {@code null} at capture
 *       time, so a never-customized player occupies no budget and no restore
 *       touches anything on the target.</li>
 *   <li>Each component JSON may not exceed {@value #MAX_COMPONENT_CHARS}
 *       characters — capture fails loudly rather than persist a truncated
 *       name; validation rejects the payload outright.</li>
 * </ul>
 *
 * <p>This record is a dumb DTO: structural checks live in
 * {@link MetadataPayloadValidation} so a corrupt stored payload surfaces as a
 * validation failure with a readable reason instead of an opaque decode
 * error. Stored payloads older than {@value #FORMAT} are not migrated —
 * there is exactly one schema, anything else is rejected by validation.</p>
 *
 * @param format          payload format version, always {@value #FORMAT}
 * @param displayName     gson component JSON of the custom display name, or null when not customized
 * @param playerListName  gson component JSON of the custom tab-list name, or null when not customized
 */
public record MetadataPayload(int format, @Nullable String displayName, @Nullable String playerListName) {

    /** Current wire format. Bump when the serialized shape changes incompatibly. */
    public static final int FORMAT = 1;

    /**
     * Maximum number of characters allowed for one serialized component. Chat
     * display names with gradients, fonts, hover events and click actions stay
     * far below this in practice; it exists to bounce corrupt or hand-edited
     * payloads before they reach the live player, and to cap snapshot size per
     * key. Enforced by capture (as a loud failure) and by validation (as a
     * rejection).
     */
    public static final int MAX_COMPONENT_CHARS = 16_384;
}
