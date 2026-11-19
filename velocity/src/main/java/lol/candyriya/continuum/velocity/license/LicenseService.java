package lol.candyriya.continuum.velocity.license;

/**
 * The license service the command surface dispatches to.
 *
 * <p>Bound in the services graph, so swapping the implementation never touches
 * commands. Implementations should be fast and non-blocking where reasonable;
 * callers run on the command execution path.</p>
 */
public interface LicenseService {

    /** Redeems a license code. */
    LicenseReport redeem(String code);

    /** Reports the state of the currently registered license. */
    LicenseReport check();

    /** Clears locally registered license state. */
    LicenseReport reset();
}
