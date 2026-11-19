package lol.candyriya.continuum.paper.modules.location;

/**
 * What happens when the snapshot's world is not usable on this node.
 *
 * <p>The placement decision itself — where the player actually appears — is always the
 * gate-time spawn policy's business (see the module javadoc for the two-layer contract).
 * The fallback exists so the module knows whether it may attempt anything at all: every
 * current fallback means "the module steps aside, the platform decides".</p>
 */
public enum FallbackMode {

    /**
     * Do nothing about the position and let the gate-time spawn policy decide (its own
     * fallback for an unavailable last-known location is the server spawn). The module
     * logs the skip; the player stays where the gate put them.
     */
    SERVER_SPAWN
}
