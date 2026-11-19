package lol.candyriya.continuum.velocity.license;

/**
 * Honest default used until a real license backend is wired in: nothing is
 * redeemed, nothing is checked against a server, nothing is faked. Every
 * operation reports {@code NOT_CONFIGURED}; the command layer renders that as
 * "not configured yet" and never as a fake success.
 */
public final class NotConfiguredLicenseService implements LicenseService {

    private static final String DETAIL = "no license service is wired into this build";

    @Override
    public LicenseReport redeem(String code) {
        return LicenseReport.of(LicenseState.NOT_CONFIGURED, DETAIL);
    }

    @Override
    public LicenseReport check() {
        return LicenseReport.of(LicenseState.NOT_CONFIGURED, DETAIL);
    }

    @Override
    public LicenseReport reset() {
        return LicenseReport.of(LicenseState.NOT_CONFIGURED, DETAIL);
    }
}
