package lol.candyriya.continuum.paper.modules.attributes;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

/**
 * Registry-backed catalog. The attribute registry is immutable once the server has
 * booted, so reading it from the player's scheduler (entity region) thread is a
 * safe read; no global-region hop is needed for a streaming peek.
 */
final class RegistryAttributeCatalog implements AttributeCatalog {

    @Override
    public Stream<Attribute> all() {
        return Registry.ATTRIBUTE.stream();
    }

    @Override
    public Attribute byKey(String key) {
        if (!AttributeKeys.isValid(key)) {
            return null;
        }
        return Registry.ATTRIBUTE.get(NamespacedKey.fromString(key));
    }
}
