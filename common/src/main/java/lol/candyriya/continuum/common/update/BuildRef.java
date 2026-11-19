package lol.candyriya.continuum.common.update;

/**
 * Parsed form of the running build's version string ({@code BuildConstants.VERSION}).
 *
 * <p>Release CI stamps {@code build-<N>}, the dev channel stamps
 * {@code devbuild-<N>}; anything else (local 1.0.0 working tree builds) is
 * recognized as {@link Kind#UNKNOWN} and the updater declines to ask the
 * service anything it cannot answer truthfully.</p>
 */
public record BuildRef(String raw, Kind kind, int number) {

    public enum Kind {
        /** {@code build-<N>} — release-channel build. */
        RELEASE,
        /** {@code devbuild-<N>} — dev-channel build. */
        DEV,
        /** Not a build-stamped version (dev working trees, source builds). */
        UNKNOWN
    }

    public BuildRef {
        if (raw == null) {
            raw = "";
        } else {
            raw = raw.trim();
        }
    }

    /** Parses leniently, never throws; unrecognizable versions come back as {@link Kind#UNKNOWN}. */
    public static BuildRef parse(String version) {
        String v = version == null ? "" : version.trim();
        Kind kind = null;
        String digits = null;
        if (v.regionMatches(true, 0, "devbuild-", 0, "devbuild-".length())) {
            kind = Kind.DEV;
            digits = v.substring("devbuild-".length());
        } else if (v.regionMatches(true, 0, "build-", 0, "build-".length())) {
            kind = Kind.RELEASE;
            digits = v.substring("build-".length());
        }
        if (kind == null) {
            return new BuildRef(v, Kind.UNKNOWN, -1);
        }
        try {
            int number = Integer.parseInt(digits);
            if (number < 0) {
                return new BuildRef(v, Kind.UNKNOWN, -1);
            }
            return new BuildRef(v, kind, number);
        } catch (NumberFormatException e) {
            return new BuildRef(v, Kind.UNKNOWN, -1);
        }
    }

    /** True when this is a build-stamped version the update service can reason about. */
    public boolean isStamped() {
        return kind != Kind.UNKNOWN;
    }

    /**
     * The {@code build} query parameter to send for {@code channel}.
     *
     * <p>A release-channel server must advertise {@code build-<N>} and a
     * dev-channel server {@code devbuild-<N>} — the service enforces the match
     * with a 400, so answering the wrong channel on purpose is worse than
     * answering not at all. Returns null when the running build cannot be
     * expressed truthfully on the requested channel.</p>
     */
    public String queryParam(UpdateChannel channel) {
        if (!isStamped()) {
            return null;
        }
        return switch (channel) {
            case RELEASE -> kind == Kind.RELEASE ? "build-" + number : null;
            case DEV -> "devbuild-" + number;
        };
    }
}
