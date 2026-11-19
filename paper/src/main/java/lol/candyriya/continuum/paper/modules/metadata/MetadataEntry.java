package lol.candyriya.continuum.paper.modules.metadata;

import java.util.List;

/**
 * The auditable decision matrix for everything on the 26.3 platform that smells
 * like player "metadata". Each constant is one surface a well-meaning plugin
 * might touch on a {@code Player}, and each carries this module's verdict plus
 * the reason. The module code consults {@link #applicable()} and never reaches
 * outside it — the rest of the surface exists in this enum only so the split is
 * written down in one place, machine-checked by tests, and honest about why.
 *
 * <p>The split is the design, not a compromise:</p>
 * <ul>
 *   <li><b>Synced tier — persistent-ish profile presentation owned by plugins.</b>
 *       The display name and the tab-list name are the two values plugins set to
 *       represent the player (role, group, clan, patron flair). They are not
 *       persisted anywhere by the server, so a backend switch silently drops
 *       them; syncing them is the whole point of this module. Both round-trip
 *       as adventure gson component JSON, which is the format the platform
 *       itself ships for component payloads.</li>
 *   <li><b>Excluded tier — runtime gameplay/presentation state.</b> Entity
 *       shared flags (glow, invisibility, invulnerability, freeze ticks,
 *       collidable), send-only clientbound state (tab-list header/footer),
 *       read-only hints (locale, brand), anything derived from the scoreboard
 *       family, the Bukkit {@code Metadatable} store, and anything another
 *       module already owns. All of it is server-local by construction: either
 *       it decays on its own tick, the owning backend re-derives it, or a
 *       second module rewrites it on join.</li>
 * </ul>
 *
 * <p>Verdicts are final for this schema version. Moving an entry between tiers
 * is a schema decision, not an implementation detail.</p>
 */
public enum MetadataEntry {

    // ---- synced tier ----------------------------------------------------

    /**
     * {@code org.bukkit.entity.Player#displayName()} /
     * {@code #displayName(Component)} (Paper). The friendly name shown in chat
     * and plugins that tint it with roles or banners. Runtime per-session value —
     * the server persists it nowhere — but it represents the player's profile as
     * plugins build it, which is exactly what must survive a backend switch.
     */
    DISPLAY_NAME(Verdict.SYNCED, "display_name",
            "Paper Player#displayName()/displayName(Component): plugin-set profile presentation, "
                    + "not persisted by the server anywhere, round-trippable via adventure gson component JSON; "
                    + "restoring the mirror is the point of this module"),

    /**
     * {@code org.bukkit.entity.Player#playerListName()} /
     * {@code #playerListName(Component)} (Paper). The name shown on the tab
     * list; {@code playerListName(null)} resets to the plain player name.
     */
    PLAYER_LIST_NAME(Verdict.SYNCED, "player_list_name",
            "Paper Player#playerListName()/playerListName(Component): plugin-set tab-list presentation, "
                    + "not persisted by the server anywhere, null setter resets to the plain name; "
                    + "restoring the mirror is the point of this module"),

    // ---- excluded: runtime entity flags (decay, re-derived, backend-owned) ----

    /** {@code Entity#isGlowing()} / {@code #setGlowing(boolean)} — Entity shared flag, not persisted for players. */
    GLOWING(Verdict.EXCLUDED, null,
            "Entity#isGlowing/setGlowing: runtime shared flag owned per backend by highlight/vanish tooling; "
                    + "mirroring would leak an unmanaged client-side outline onto every backend"),

    /** {@code Entity#isInvisible()} / {@code #setInvisible(boolean)} — Entity shared flag, not persisted for players. */
    INVISIBILITY(Verdict.EXCLUDED, null,
            "Entity#isInvisible/setInvisible: runtime shared flag owned per backend by vanish tooling; "
                    + "the target must not silently inherit invisibility from a different ruleset"),

    /** {@code Entity#isMarkedInvulnerable()} / {@code #setInvulnerable(boolean)} — gameplay safety toggle. */
    INVULNERABILITY(Verdict.EXCLUDED, null,
            "Entity#isMarkedInvulnerable/setInvulnerable/isInvulnerableTo: gameplay policy (god/cheat plugins, minigames); "
                    + "mirroring unkillability onto a backend that never decided it is a correctness bug, not sync"),

    /** {@code Entity#setFreezeTicks(int)} / {@code #lockFreezeTicks(boolean)} — decaying runtime counter. */
    FREEZE_TICKS(Verdict.EXCLUDED, null,
            "Entity#setFreezeTicks/getFreezeTicks/lockFreezeTicks: powder-snow/combat freeze timer the server decays every tick; "
                    + "a stored value is meaningless by the time a snapshot is restored"),

    /** {@code LivingEntity#setCollidable(boolean)} — the API itself forbids it for players. */
    COLLIDABLE(Verdict.EXCLUDED, null,
            "LivingEntity#setCollidable/isCollidable: 26.3 javadoc restricts it to non-player entities — "
                    + "player collision is client-predicted and controlled via scoreboard team COLLISION_RULE (out of scope); "
                    + "syncing it would be syncing a documented no-op"),

    // ---- excluded: no-ops, read-only hints, derived presentation ----

    /** {@code Nameable#customName()} — javadoc: no effect on players at all. */
    CUSTOM_NAME(Verdict.EXCLUDED, null,
            "Nameable#customName()/customName(Component): 26.3 javadoc states it has no effect on players — "
                    + "they always use their real name; nothing to sync"),

    /** {@code Player#getPlayerListOrder()} / {@code #setPlayerListOrder(int)} — tab-list sort hint. */
    PLAYER_LIST_ORDER(Verdict.EXCLUDED, null,
            "Player#getPlayerListOrder/setPlayerListOrder(int): client-side tab-list sort hint, the parameter must be positive "
                    + "so there is no encoding for 'unset'; runtime per-backend presentation, not state"),

    /** {@code Player#playerListHeader()} / {@code #playerListFooter()} + the send-only header/footer audience calls. */
    TABLIST_HEADER_FOOTER(Verdict.EXCLUDED, null,
            "Player#playerListHeader()/playerListFooter() and Audience#sendPlayerListHeaderAndFooter(Component, Component): "
                    + "clientbound send-only state, tab-list plugins re-render it on every join (usually placeholder-driven); "
                    + "a restored capture duplicates or freezes that render"),

    /** {@code Player#getLocale()} — client hint, read-only. */
    LOCALE(Verdict.EXCLUDED, null,
            "Player#getLocale(): read-only client-reported hint consumed by the backend's locale resolution; "
                    + "there is no setter, so there is nothing to restore"),

    /** {@code Player#getClientBrandName()} — read-only, diagnostic. */
    CLIENT_BRAND(Verdict.EXCLUDED, null,
            "Player#getClientBrandName(): read-only diagnostic, @Nullable in 26.3; never restorable"),

    /** {@code Player#getDeathScreenScore()} / {@code #setDeathScreenScore(int)} — derived from scoreboards. */
    DEATH_SCREEN_SCORE(Verdict.EXCLUDED, null,
            "Player#getDeathScreenScore/setDeathScreenScore(int): death-screen presentation derived from scoreboard state; "
                    + "scoreboard handling is out of scope for this module and a restored integer means nothing on a fresh backend"),

    /** {@code Entity#teamDisplayName()} — composed from the scoreboard team. */
    TEAM_DISPLAY_NAME(Verdict.EXCLUDED, null,
            "Entity#teamDisplayName(): composed at runtime from the entity's scoreboard team prefix/suffix and name; "
                    + "it is not independent state, and scoreboard teams are out of scope for this module"),

    // ---- excluded: stores and families owned elsewhere or never allowed ----

    /** Bukkit {@code Metadatable} — the deprecated in-memory metadata store. */
    BUKKIT_METADATA(Verdict.EXCLUDED, null,
            "Metadatable#setMetadata/getMetadata/hasMetadata/removeMetadata: deprecated (javadoc: \"extremely misleading\", "
                    + "no cleanup on entity removal, no wire format, untyped per-plugin store); the sanctioned persistent store is the "
                    + "PDC owned by continuum.pdc — this module never touches it"),

    /** Scoreboard team membership and options — a different domain entirely. */
    SCOREBOARD_TEAMS(Verdict.EXCLUDED, null,
            "Team membership and team options (prefix/suffix, collision rule, nametag visibility) via the scoreboard API: "
                    + "scoreboard handling is out of scope for this module; the scoreboard backend owns it per server"),

    /** PersistentDataContainer — owned by {@code continuum.pdc}. */
    PERSISTENT_DATA_CONTAINER(Verdict.EXCLUDED, null,
            "Entity#getPersistentDataContainer(): the Player PDC is persisted by the server and owned by the continuum.pdc module; "
                    + "this module never reads or writes it — one owner per store"),

    /** Respawn (bed) location — persistent, but location-domain. */
    RESPAWN_LOCATION(Verdict.EXCLUDED, null,
            "Player#getRespawnLocation()/setRespawnLocation(Location): persistent respawn/bed state; a Location carries a "
                    + "world reference and world identity across backends is the location module's problem, not this one's"),

    /** Last death location — persistent, but location-domain. */
    LAST_DEATH_LOCATION(Verdict.EXCLUDED, null,
            "HumanEntity#getLastDeathLocation()/setLastDeathLocation(Location): persistent, but a Location is world-indexed state; "
                    + "owned by the location domain, never by this module"),

    /** Compass target — location-domain. */
    COMPASS_TARGET(Verdict.EXCLUDED, null,
            "Player#getCompassTarget()/setCompassTarget(Location): world-indexed position state owned by the location domain; "
                    + "a cross-backend restore is meaningless without world mapping"),

    /** Flight and movement ability flags — owned by the gamemode module and per-backend policy. */
    FLIGHT_ABILITIES(Verdict.EXCLUDED, null,
            "Player#getAllowFlight/setAllowFlight/isFlying/setFlying/getFlySpeed/setFlySpeed/getWalkSpeed/setWalkSpeed: "
                    + "vanilla abilities are persisted in player data, but they are owned by the gamemode module (continuum.gamemode) "
                    + "together with per-backend flight permission policy; mirroring them leaks flight onto restrictive backends"),

    /** Sneak/sprint/glide pose flags — owned by the gamemode module. */
    MOVEMENT_FLAGS(Verdict.EXCLUDED, null,
            "Player#isSneaking/isSprinting/isGliding: pose flags restored with the gamemode family by the gamemode module "
                    + "(continuum.gamemode); not presentation metadata and not owned here"),

    /** Skin — session-server-signed identity, not server state. */
    SKIN(Verdict.EXCLUDED, null,
            "PlayerProfile properties (skin textures) are signed by the session server; a backend has no business "
                    + "re-signing or overriding identity material per server, and the proxy/session owns profile resolution"),

    /** Health, food, saturation — vitals module. */
    VITALS(Verdict.EXCLUDED, null,
            "HumanEntity#getHealth/setHealth, FoodLevel etc.: vitals family; owned by continuum.vitals with attribute gating; "
                    + "not presentation metadata and not owned here"),

    /** Experience — experience module. */
    EXPERIENCE(Verdict.EXCLUDED, null,
            "Player#getLevel/setLevel, getExp/setExp, total experience: experience family; owned by continuum.experience; "
                    + "not presentation metadata and not owned here"),

    /** Inventory and ender chest — inventory modules. */
    INVENTORY(Verdict.EXCLUDED, null,
            "Player inventory and ender chest contents: inventory family; owned by continuum.inventory / continuum.enderchest; "
                    + "not presentation metadata and not owned here"),

    /** Advancements, recipes, statistics — their own families. */
    PROGRESSION(Verdict.EXCLUDED, null,
            "Advancements, recipe book, statistics, attributes: progression/state families; owned by continuum.advancements, "
                    + "continuum.recipes, continuum.statistics, continuum.attributes; not presentation metadata and not owned here");

    /** Verdict partition for the matrix. */
    public enum Verdict {
        /** Synced by this module, round-trippable, restored as an exact mirror. */
        SYNCED,
        /** Never read or written by this module; the reason says who owns it or why it must not travel. */
        EXCLUDED
    }

    private final Verdict verdict;
    private final String wireKey;
    private final String reason;

    MetadataEntry(Verdict verdict, String wireKey, String reason) {
        this.verdict = verdict;
        this.wireKey = wireKey;
        this.reason = reason;
    }

    /** The verdict for this entry. */
    public Verdict verdict() {
        return verdict;
    }

    /**
     * The key under which this entry is stored in the wire payload, or {@code null} for excluded entries.
     * Synced entries always carry one; it is the snake_case record component name used by the shared mapper.
     */
    public String wireKey() {
        return wireKey;
    }

    /** Why this entry is synced or excluded — always specific, never blank. */
    public String reason() {
        return reason;
    }

    /** The entries this module captures and restores, in wire order. */
    public static List<MetadataEntry> applicable() {
        return List.of(DISPLAY_NAME, PLAYER_LIST_NAME);
    }

    /** Every excluded entry, for audit. */
    public static List<MetadataEntry> excluded() {
        return java.util.Arrays.stream(values())
                .filter(entry -> entry.verdict == Verdict.EXCLUDED)
                .toList();
    }
}
