package lol.candyriya.continuum.common.config;

/**
 * What this node in the network is. Backends (Paper/Folia) hold player state;
 * the coordinator (Velocity) routes connections and owns session orchestration.
 */
public enum ServerRole {

    /** Paper/Folia backend: captures and restores player state. */
    BACKEND,

    /** Velocity coordinator: session orchestration and transfer routing. */
    COORDINATOR
}
