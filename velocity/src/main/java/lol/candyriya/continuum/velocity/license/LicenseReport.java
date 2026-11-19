package lol.candyriya.continuum.velocity.license;

import java.util.Objects;

/**
 * Result of a license operation.
 *
 * @param state  what happened, machine-readable
 * @param detail free-form English detail for logs; never echoed into chat verbatim
 */
public record LicenseReport(LicenseState state, String detail) {

    public LicenseReport {
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail;
    }

    public static LicenseReport of(LicenseState state, String detail) {
        return new LicenseReport(state, detail);
    }
}
