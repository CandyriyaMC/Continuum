package lol.candyriya.continuum.velocity;

// Version is injected before compilation by the generateBuildConstants task,
// so the @Plugin annotation sees a constant instead of a hardcoded string.
// NOTE: keep this file ASCII-only, expand() mangles non-ASCII (see velocity/build.gradle.kts).
public final class BuildConstants {

    public static final String VERSION = "${version}";

    private BuildConstants() {
        // constants holder, not meant to be instantiated
    }
}
