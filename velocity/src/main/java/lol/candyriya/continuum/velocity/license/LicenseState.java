package lol.candyriya.continuum.velocity.license;

/** Outcome states a license operation can report. */
public enum LicenseState {
    /** License present and valid for this build. */
    VALID,
    /** No license on file. Continuum still runs; support does not. */
    MISSING,
    /** License expired. Continuum still runs; support does not. */
    EXPIRED,
    /** License does not cover this build's version. */
    VERSION_MISMATCH,
    /** The command ran, but no license service is wired into this build. */
    NOT_CONFIGURED,
    /** The code handed to redeem cannot be redeemed. */
    INVALID_CODE,
    /** The service was reached but the operation failed. Details are logged, not shown. */
    ERROR
}
