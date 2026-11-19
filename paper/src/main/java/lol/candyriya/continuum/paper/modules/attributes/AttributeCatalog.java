package lol.candyriya.continuum.paper.modules.attributes;

import org.bukkit.attribute.Attribute;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

/**
 * How the module finds attributes. The default implementation reads
 * {@code Registry.ATTRIBUTE} (data-driven, so modded and datapack-added attributes are
 * included automatically); tests inject a fake catalog for the same code paths.
 */
interface AttributeCatalog {

    /** All attributes registered on this server, in registry order. */
    Stream<Attribute> all();

    /**
     * Look up an attribute by its namespaced key, or {@code null} when this server
     * does not know it (e.g. a modded attribute captured elsewhere). Callers must
     * tolerate null — skipping is the documented behavior, never an exception.
     */
    @Nullable
    Attribute byKey(String key);
}
