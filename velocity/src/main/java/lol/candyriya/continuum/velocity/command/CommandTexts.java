package lol.candyriya.continuum.velocity.command;

import lol.candyriya.continuum.common.i18n.TypedArgument;

import com.velocitypowered.api.command.CommandSource;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;

/**
 * MiniMessage rendering for the proxy's operational commands.
 *
 * <p>The proxy overlay ({@code velocity-locales/en_US.yml}) exists for the
 * keys the skeleton introduced; the operational surface this package adds
 * carries its strings here instead, as English MiniMessage templates with
 * named, unparsed placeholders. The rendering pipeline mirrors the catalog
 * path exactly (MiniMessage + {@link TypedArgument} semantics), and every
 * line carries the same {@code [Continuum]} bracket the catalogs put in
 * front of the existing messages.</p>
 *
 * <p>Template constants are named after the catalog key each one maps to,
 * so moving a template into the catalogs later is a search-and-replace, not
 * a rewrite. Argument names match the {@code <tag>} in the template, never
 * paste raw input into tags - every value goes in through a typed, unparsed
 * placeholder.</p>
 *
 * <p>Thread-safe; the holder types are stateless.</p>
 */
final class CommandTexts {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Must match {@code velocity.prefix} in the proxy locales; keep in sync. */
    private static final String PREFIX_TEMPLATE = "<gold>[Continuum]</gold> ";

    private CommandTexts() {
    }

    // --- player inspection ---

    /** Args: player (text), uuid (text) */
    static final String PLAYER_INFO_HEADER = "<gold>Player <white><player></white> <gray>(<uuid>)</gray>";
    /** Args: backend (text) */
    static final String PLAYER_INFO_BACKEND = "<gray>Backend:</gray> <white><backend></white>";
    /** Args: seconds (number) */
    static final String PLAYER_INFO_BACKEND_AWAITING =
            "<gray>Backend:</gray> <yellow>none - awaiting a backend outcome (<seconds>s)</yellow>";
    /** Args: reason (text); only rendered when an awaiting reason is present */
    static final String PLAYER_INFO_AWAITING_REASON = "<gray>Awaiting reason:</gray> <white><reason></white>";
    /** Args: iso (text), ago (text) */
    static final String PLAYER_INFO_SINCE = "<gray>On this backend since:</gray> <white><iso></white> <dark_gray>(<ago> ago)</dark_gray>";
    /** Args: iso (text), ago (text) - the awaiting marker time instead of the connect time */
    static final String PLAYER_INFO_AWAITING_SINCE = "<gray>Awaiting outcome since:</gray> <white><iso></white> <dark_gray>(<ago> ago)</dark_gray>";
    /** Args: count (number), window (number) */
    static final String PLAYER_INFO_TRANSITIONS = "<gray>Reconnect transitions (last <window>s):</gray> <white><count></white>";
    /** Args: state (text) */
    static final String PLAYER_INFO_PLACEMENT = "<gray>Placement:</gray> <white><state></white>";
    /** Args: player (text) */
    static final String PLAYER_INFO_NO_DATA =
            "<yellow>No session data for <white><player></white> is tracked on this node.</yellow> <gray>The player may be online through another proxy, or not connected at all.</gray>";
    static final String PLAYER_INFO_STORAGE_HEAD = "<gray>Storage-backed state on this proxy:</gray>";
    static final String PLAYER_INFO_FENCING = "<gray>fencing token:</gray> <yellow>unavailable - session storage is not connected on this proxy yet</yellow>";
    static final String PLAYER_INFO_SNAPSHOT = "<gray>last snapshot:</gray> <yellow>unavailable - snapshot storage is not connected on this proxy yet</yellow>";
    /** Args: mode (text) - online or offline */
    static final String PLAYER_INFO_ONLINE_MODE = "<gray>Online mode:</gray> <white><mode></white>";
    static final String PLAYER_INFO_ONLINE_MODE_ONLINE = "online (Mojang authentication)";
    static final String PLAYER_INFO_ONLINE_MODE_OFFLINE = "offline (no authentication; backend session fencing is the only authority)";

    /** Args: command (text) - the operation name, detail (text) - the reason it cannot run */
    static final String PLAYER_ACTION_UNAVAILABLE =
            "<yellow><command> <detail></yellow>";
    /** Args: command (text), player (text), detail (text) - backend-confirmed outcome */
    static final String PLAYER_ACTION_DONE =
            "<green><command> ran for <white><player></white>.</green> <gray><detail></gray>";

    // --- backend ---

    /** Args: count (number) */
    static final String BACKEND_LIST_HEADER = "<gold>Registered backends (<count>)</gold>";
    static final String BACKEND_LIST_EMPTY = "<gray>No backends registered. Check velocity.toml.</gray>";
    /** Args: name (text), address (text), connections (number), frames (number) */
    static final String BACKEND_LIST_ENTRY =
            "<yellow><name></yellow> <dark_gray>(<address>)</dark_gray> - <white><connections></white> <gray>connection(s), <frames> messaging frame(s)</gray>";
    /** Args: name (text), address (text), connections (number), frames (number) */
    static final String BACKEND_STATUS = "<gold>Backend <white><name></white></gold>";
    /** Args: address (text) */
    static final String BACKEND_STATUS_ADDRESS = "<gray>Address:</gray> <white><address></white>";
    /** Args: connections (number) */
    static final String BACKEND_STATUS_CONNECTIONS = "<gray>Tracked connections:</gray> <white><connections></white>";
    /** Args: frames (number) */
    static final String BACKEND_STATUS_FRAMES = "<gray>Plugin-message frames received:</gray> <white><frames></white>";

    // --- modules ---

    /** Args: count (number) */
    static final String MODULE_LIST_HEADER = "<gold>Built-in modules (<count>)</gold>";
    /** Args: id (text), state (text), config (text) */
    static final String MODULE_LIST_ENTRY = "<yellow><id></yellow> <white><state></white> <dark_gray>(<config>)</dark_gray>";
    static final String MODULE_LIST_FOOTER =
            "<dark_gray>Third-party adapters register on backends at runtime and are not visible to this proxy.</dark_gray>";
    static final String MODULE_NAMESPACE_NOTE =
            "<dark_gray>custom.* is reserved for third-party adapters; they are unknown to this proxy until a backend registers them.</dark_gray>";
    /** Args: id (text), source (text) */
    static final String MODULE_INFO_HEADER = "<gold>Module <white><id></white></gold>";
    /** Args: source (text) */
    static final String MODULE_INFO_SOURCE = "<gray>Source:</gray> <white><source></white>";
    static final String MODULE_INFO_NAMESPACE = "<gray>Namespace:</gray> <white>continuum.* (reserved for first-party modules)</white>";
    /** Args: config (text), state (text) */
    static final String MODULE_INFO_CONFIG = "<gray>Config key:</gray> <white><config></white> <gray>- effective:</gray> <white><state></white>";
    static final String MODULE_INFO_CONFIG_NONE = "<gray>Config key:</gray> <white>none</white> <gray>(not gated by sync.modules)</gray>";
    /** Args: position (number), total (number) */
    static final String MODULE_INFO_ORDER = "<gray>Default restore order:</gray> <white>#<position> of <total></white>";
    static final String MODULE_INFO_ORDER_NONE = "<gray>Default restore order:</gray> <white>none</white> <gray>(restored via module dependencies)</gray>";
    static final String MODULE_INFO_SCHEMA =
            "<dark_gray>Schema version, priority and live toggles are reported by the backend that registered the module; this proxy does not hold a module registry.</dark_gray>";
    /** Args: query (text) */
    static final String MODULE_INFO_UNKNOWN =
            "<red>Unknown module '<query>'. Run /continuum module list to see the known module ids.</red>";
    static final String MODULE_INFO_CUSTOM =
            "<gold>Module custom.*</gold> <gray>reserved namespace for third-party adapters</gray>";
    static final String MODULE_INFO_CUSTOM_DETAIL =
            "<white>Third-party modules built on the adapter API declare their own ids in their own namespaces.</white> <dark_gray>They are not enumerable from this proxy.</dark_gray>";

    // --- diagnostics ---

    static final String DIAGNOSTICS_HEADER = "<gold>Continuum diagnostics</gold>";
    /** Args: server (text), network (text), role (text) */
    static final String DIAGNOSTICS_NODE = "<gray>Node:</gray> <white><server></white> <gray>(<network>, role <role>)</gray>";
    /** Args: continuum (text), velocity (text) */
    static final String DIAGNOSTICS_BUILD = "<gray>Build:</gray> <white>Continuum <continuum></white> <gray>on Velocity <velocity></gray>";
    /** Args: debug (text) */
    static final String DIAGNOSTICS_DEBUG = "<gray>debug:</gray> <white><debug></white>";
    /** Args: type (text), detail (text) */
    static final String DIAGNOSTICS_STORAGE = "<gray>storage:</gray> <white><type></white> <dark_gray>(<detail>)</dark_gray>";
    static final String DIAGNOSTICS_STORAGE_PROXY = "<gray>session persistence on this proxy:</gray> <white>not connected</white> <dark_gray>(backends own session state)</dark_gray>";
    /** Args: enabled (text) */
    static final String DIAGNOSTICS_RABBITMQ = "<gray>rabbitmq transport:</gray> <white><enabled></white>";
    /** Args: enabled (text), channel (text), max (number) */
    static final String DIAGNOSTICS_MESSAGING = "<gray>plugin messaging:</gray> <white><enabled></white> <gray>channel</gray> <white><channel></white> <gray>max frame</gray> <white><max></white> <gray>bytes</gray>";
    /** Args: locale (text) - default locale, fallback (text) - fallback locale, client (text) - use-client-locale on/off */
    static final String DIAGNOSTICS_I18N = "<gray>i18n:</gray> <white>default <locale></white> <gray>fallback</gray> <white><fallback></white> <gray>client-locale</gray> <white><client></white>";
    /** Args: enabled (text), interval (number) */
    static final String DIAGNOSTICS_AUTOSAVE = "<gray>autosave:</gray> <white><enabled></white> <gray>every</gray> <white><interval>s</white>";
    /** Args: require_snapshot (text) - on/off, restore_before_play (text) - on/off */
    static final String DIAGNOSTICS_TRANSFER = "<gray>transfer:</gray> <white>require-snapshot <require_snapshot></white> <gray>restore-before-play</gray> <white><restore_before_play></white>";
    /** Args: enabled (text), interval (number), order (text) */
    static final String DIAGNOSTICS_RECOVERY = "<gray>recovery:</gray> <white><enabled></white> <gray>every</gray> <white><interval>s</white> <gray>restore-order</gray> <white><order></white>";
    /** Args: gzip (text) - on/off, max_bytes (number) */
    static final String DIAGNOSTICS_SERIALIZATION = "<gray>serialization:</gray> <white>gzip <gzip></white> <gray>max snapshot</gray> <white><max_bytes></white> <gray>bytes</gray>";
    /** Args: seconds (number) */
    static final String DIAGNOSTICS_DEBOUNCE = "<gray>reconnect debounce:</gray> <white><seconds>s</white>";
    static final String DIAGNOSTICS_RUNTIME_HEADER = "<gray>Runtime:</gray>";
    /** Args: count (number) */
    static final String DIAGNOSTICS_BACKENDS = "<gray>registered backends:</gray> <white><count></white>";
    /** Args: online (number), awaiting (number) */
    static final String DIAGNOSTICS_TRACKED = "<gray>players tracked:</gray> <white><online></white> <gray>(+<awaiting> awaiting a backend outcome)</gray>";
    /** Args: total (number), last (text) */
    static final String DIAGNOSTICS_TRAFFIC = "<gray>messaging frames accepted:</gray> <white><total></white> <gray>last</gray> <white><last></white>";
    static final String DIAGNOSTICS_TRAFFIC_NEVER = "never";
    static final String DIAGNOSTICS_TRAFFIC_EMPTY = "<gray>frames by backend:</gray> <white>none</white>";
    /** Args: name (text), frames (number) */
    static final String DIAGNOSTICS_TRAFFIC_ENTRY = "<gray>frames by backend:</gray> <yellow><name></yellow> <white><frames></white>";
    static final String DIAGNOSTICS_TOKENS = "<gray>session tokens:</gray> <yellow>unavailable</yellow> <dark_gray>(storage-backed fencing is not connected on this proxy yet)</dark_gray>";

    // --- help ---

    /** Args: command (text), description (text), permission (text) */
    static final String HELP_ENTRY =
            "<yellow><command></yellow> <gray>-</gray> <white><description></white> <dark_gray>[<permission>]</dark_gray>";
    /**
     * Renders {@code template} with named placeholders. Text and number
     * arguments are unparsed (user-controlled content cannot smuggle markup
     * in); templates are build-time constants, not user input.
     */
    static Component render(String template, TypedArgument... args) {
        TagResolver[] resolvers = new TagResolver[args.length];
        for (int i = 0; i < args.length; i++) {
            TypedArgument arg = args[i];
            resolvers[i] = switch (arg) {
                case TypedArgument.Text text -> Placeholder.unparsed(text.argument(), text.value());
                case TypedArgument.NumberValue number ->
                        Placeholder.unparsed(number.argument(), String.valueOf(number.value()));
                case TypedArgument.Flag flag ->
                        Placeholder.unparsed(flag.argument(), String.valueOf(flag.value()));
                case TypedArgument.ComponentValue component ->
                        Placeholder.component(component.argument(), component.value());
            };
        }
        return MINI.deserialize(template, resolvers);
    }

    /** Multi-line document: header line plus one line per body component, newline separated. */
    static Component document(Component header, List<Component> lines) {
        Component document = header;
        for (Component line : lines) {
            document = document.append(Component.newline()).append(line);
        }
        return document;
    }

    /** Prepends the Continuum bracket once. */
    static Component prefixed(Component body) {
        return Component.empty().append(MINI.deserialize(PREFIX_TEMPLATE)).append(body);
    }

    /** Sends a single, prefixed line to the source. */
    static void send(CommandSource source, Component line) {
        source.sendMessage(prefixed(line));
    }

    /** Sends a prefixed multi-line document to the source. */
    static void sendDocument(CommandSource source, Component document) {
        source.sendMessage(prefixed(document));
    }
}
