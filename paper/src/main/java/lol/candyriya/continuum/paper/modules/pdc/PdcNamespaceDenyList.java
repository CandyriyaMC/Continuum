package lol.candyriya.continuum.paper.modules.pdc;

import java.util.Locale;
import java.util.Set;

/**
 * The hard deny-list for PDC namespaces that must never be synced.
 *
 * <p><b>The inversion, stated plainly.</b> The ideal design is an allow-list: nothing
 * crosses between servers unless the operator said so. Continuum's config schema is
 * closed for this build - there is no key an operator can hang a namespace list on -
 * so this module ships the only safe closed default there is: sync everything
 * <em>except</em> namespaces that plausibly carry credentials, second-factor state or
 * verification material. Anything not on the list syncs; the listed namespaces are
 * skipped and logged (counts only, never values) at capture and restore time.</p>
 *
 * <p>This is deliberately conservative, not exhaustive. A plugin not on this list
 * could still stash something sensitive in its own namespace, and this module cannot
 * tell - PDC payloads are opaque bytes to us. Operators who do not want any PDC data
 * moved between their servers should disable the module entirely
 * ({@code sync.modules.pdc=false}); that is a supported, safe configuration with
 * zero data movement.</p>
 *
 * <p>When the config schema grows a list key for this module, this class becomes the
 * built-in default underneath the operator override, not a replacement for it.</p>
 */
public final class PdcNamespaceDenyList {

    /**
     * Namespaces that are never captured or restored, whatever the snapshot says.
     * Exact namespace matches, lowercase; namespaces are lowercase by construction
     * (vanilla rejects anything else).
     */
    public static final Set<String> ALWAYS_DENY = Set.of(
            "security",
            "auth",
            "authy",
            "2fa",
            "login",
            "verification",
            "manual");

    private PdcNamespaceDenyList() {
        // constants only
    }

    /**
     * @param namespace the namespace half of a namespaced key (not the full key), never null
     * @return true when this namespace must not be synced
     */
    public static boolean isDenied(String namespace) {
        return ALWAYS_DENY.contains(namespace.toLowerCase(Locale.ROOT));
    }

    /** @return the denied namespaces, unmodifiable; for docs and diagnostics */
    public static Set<String> alwaysDenyNamespaces() {
        return ALWAYS_DENY;
    }
}
