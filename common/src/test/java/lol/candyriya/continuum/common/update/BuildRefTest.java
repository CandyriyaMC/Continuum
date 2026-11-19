package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildRefTest {

    @Test
    void parsesReleaseBuilds() {
        BuildRef ref = BuildRef.parse("build-119");
        assertEquals(BuildRef.Kind.RELEASE, ref.kind());
        assertEquals(119, ref.number());
        assertTrue(ref.isStamped());
    }

    @Test
    void parsesDevBuilds() {
        BuildRef ref = BuildRef.parse("devbuild-7");
        assertEquals(BuildRef.Kind.DEV, ref.kind());
        assertEquals(7, ref.number());
        assertTrue(ref.isStamped());
    }

    @Test
    void legacyVersionsAreUnstamped() {
        assertFalse(BuildRef.parse("1.0.0").isStamped());
        assertFalse(BuildRef.parse("").isStamped());
        assertFalse(BuildRef.parse(null).isStamped());
        assertFalse(BuildRef.parse("build-").isStamped());
        assertFalse(BuildRef.parse("build-abc").isStamped());
        assertFalse(BuildRef.parse("build--1").isStamped());
    }

    @Test
    void releaseQueryParamOnlyForReleaseStampedVersions() {
        assertEquals("build-119", BuildRef.parse("build-119").queryParam(UpdateChannel.RELEASE));
        // a dev jar may not claim a release build number it does not have
        assertNull(BuildRef.parse("devbuild-119").queryParam(UpdateChannel.RELEASE));
        assertNull(BuildRef.parse("1.0.0").queryParam(UpdateChannel.RELEASE));
    }

    @Test
    void devQueryParamCarriesDevBuildNumber() {
        assertEquals("devbuild-119", BuildRef.parse("devbuild-119").queryParam(UpdateChannel.DEV));
        // a release jar checking the dev channel asks for the matching devbuild entry
        assertEquals("devbuild-119", BuildRef.parse("build-119").queryParam(UpdateChannel.DEV));
        assertNull(BuildRef.parse("1.0.0").queryParam(UpdateChannel.DEV));
    }
}
