package lol.candyriya.continuum.velocity;

// Version is injected before compilation by the generateBuildConstants task,
// so the @Plugin annotation sees a constant instead of a hardcoded string.
// NOTE: keep this file ASCII-only, expand() mangles non-ASCII (see velocity/build.gradle.kts).
public class BuildConstants {
    public static final String VERSION = "${version}";
}
