package lol.candyriya.continuum.paper.modules.metadata;

import java.util.List;
import java.util.Objects;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.common.config.SyncConfig;

/**
 * Maps the {@code sync.modules.metadata} config toggle to the metadata module
 * and its persisted id ({@link BuiltinModules#METADATA}).
 *
 * <p>Honesty about wiring: a {@code SyncModule} must behave deterministically
 * once registered — flipping its behavior mid-session based on a live config
 * read would be worse than not registering it. So enablement is a
 * <em>registration-time</em> decision: the platform's module wiring consults
 * {@link #metadataEnabled} (or takes the id list from {@link #enabledModuleIds})
 * and simply constructs the module or not. The module itself is
 * toggle-agnostic.</p>
 *
 * <p>Note on the toggle key: the shared known-modules list is closed and
 * rejects unknown keys at load time, so until it gains an entry for the
 * metadata module the config loader will reject
 * <code>sync.modules.metadata</code> outright. The constant is defined here so
 * the wiring and the tests agree on the reserved key; nothing is accepted
 * silently — a typo can never disable or enable a module by accident.</p>
 */
public final class MetadataModuleToggles {

    /** The reserved config toggle key ({@code sync.modules.metadata}). */
    public static final String TOGGLE_KEY = "metadata";

    private MetadataModuleToggles() {
        // static access only
    }

    /** Whether the metadata module ({@code continuum.metadata}) is enabled; defaults to true. */
    public static boolean metadataEnabled(SyncConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        // missing key defaults to enabled — first-party modules are opt-out, mirroring the config's own defaulting
        return config.modules().getOrDefault(TOGGLE_KEY, true);
    }

    /** The persisted module id to register, empty when the toggle is off. */
    public static List<String> enabledModuleIds(SyncConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return metadataEnabled(config) ? List.of(BuiltinModules.METADATA) : List.of();
    }
}
