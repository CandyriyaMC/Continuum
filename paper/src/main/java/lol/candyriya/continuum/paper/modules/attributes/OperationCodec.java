package lol.candyriya.continuum.paper.modules.attributes;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Attribute modifier operation names, exactly as the wire format pins them:
 * {@code ADD_NUMBER}, {@code ADD_SCALAR}, {@code MULTIPLY_SCALAR_1} — the same
 * names the {@code org.bukkit.attribute.AttributeModifier.Operation} enum constants
 * carry ({@code op.name()}), so the roundtrip is lossless by construction.
 *
 * <p>Kept string-based and bukkit-free on purpose: this is where "unknown
 * operation" tolerance is decided, including at decode time, and it must be
 * testable without a server. The bukkit enum itself is only touched where it is
 * unavoidable (adding modifiers for real).</p>
 */
final class OperationCodec {

    static final String ADD_NUMBER = "ADD_NUMBER";
    static final String ADD_SCALAR = "ADD_SCALAR";
    static final String MULTIPLY_SCALAR_1 = "MULTIPLY_SCALAR_1";

    private static final List<String> KNOWN = List.of(ADD_NUMBER, ADD_SCALAR, MULTIPLY_SCALAR_1);

    private OperationCodec() {
    }

    /** Every operation name the wire format knows, as a sorted immutable set. */
    static Set<String> knownNames() {
        return Set.of(ADD_NUMBER, ADD_SCALAR, MULTIPLY_SCALAR_1);
    }

    /**
     * Canonical form of an operation name, or empty when the name is unknown.
     * The exact constant name wins; a case-insensitive match is accepted for
     * hand-written payloads. {@code null}, blank and unknown names produce empty.
     */
    static Optional<String> canonical(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        for (String known : KNOWN) {
            if (known.equals(trimmed)) {
                return Optional.of(known);
            }
        }
        for (String known : KNOWN) {
            if (known.equalsIgnoreCase(trimmed)) {
                return Optional.of(known);
            }
        }
        return Optional.empty();
    }

    static boolean isKnown(String name) {
        return canonical(name).isPresent();
    }
}
