package lol.candyriya.continuum.common.update;

/** The coarse state of the updater, for status surfaces and logs. */
public enum UpdatePhase {

    /** No public key embedded, broken update.yml, or checks turned off. */
    DISABLED,

    /** Enabled, waiting for the first check or for the next cycle. */
    IDLE,

    /** A check is running right now. */
    CHECKING,

    /** The service says we run the latest build. */
    UP_TO_DATE,

    /** A newer build exists; see the snapshot message for consent/compat details. */
    UPDATE_AVAILABLE,

    /** Verified and staged; a restart applies it. */
    STAGED,

    /** The last check blew up; the server kept working. Fail-open, always. */
    ERROR
}
