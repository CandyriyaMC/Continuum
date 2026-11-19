package lol.candyriya.continuum.paper.modules.attributes;

import lol.candyriya.continuum.api.module.BuiltinModules;

import java.util.List;

/**
 * The module's contract constants in one bukkit-free place, so the planning
 * metadata can be asserted in plain unit tests. The module itself delegates.
 */
final class AttributesContract {

    /** Lower than {@code SyncModule.DEFAULT_PRIORITY} so attributes restore before vitals. */
    static final int PRIORITY = 90;

    static final String ID = BuiltinModules.ATTRIBUTES;

    static final int SCHEMA_VERSION = 1;

    static final List<String> DEPENDENCIES = List.of();

    private AttributesContract() {
    }
}
