package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatCheckerTest {

    private static Manifest manifestMc(String mcMin, String mcMax, int javaMin, boolean folia) {
        return new Manifest(1, UpdateChannel.RELEASE, "build-120",
                "a".repeat(64), "release/build-120/x.jar", mcMin, mcMax, javaMin, folia, "2026-11-01T00:00:00.000Z");
    }

    @Test
    void versionCompareHandlesNumericSegments() {
        assertTrue(CompatChecker.compareVersions("1.9", "1.21") < 0);
        assertTrue(CompatChecker.compareVersions("1.21.4", "1.21") > 0);
        assertTrue(CompatChecker.compareVersions("26.3", "26.3") == 0);
        assertTrue(CompatChecker.compareVersions("26.3", "1.20") > 0);
        assertNull(CompatChecker.compareVersions("abc", "1.2"));
    }

    @Test
    void javaMinBlocksOlderMajors() {
        List<String> problems = CompatChecker.problems("1.21.4", 11, false,
                manifestMc("", "", 17, false));
        assertEquals(1, problems.size());
    }

    @Test
    void javaMinAllowsNewerMajors() {
        assertTrue(CompatChecker.problems("1.21.4", 25, false,
                manifestMc("", "", 17, false)).isEmpty());
    }

    @Test
    void mcBoundsAreInclusive() {
        assertTrue(CompatChecker.problems("1.20", 25, false, manifestMc("1.20", "26.3", 0, false)).isEmpty());
        assertTrue(CompatChecker.problems("26.3", 25, false, manifestMc("1.20", "26.3", 0, false)).isEmpty());
        assertEquals(1, CompatChecker.problems("1.19", 25, false, manifestMc("1.20", "26.3", 0, false)).size());
        assertEquals(1, CompatChecker.problems("26.4", 25, false, manifestMc("1.20", "26.3", 0, false)).size());
    }

    @Test
    void foliaServerRefusesNonFoliaBuild() {
        assertEquals(1, CompatChecker.problems("26.3", 25, true, manifestMc("", "", 0, false)).size());
        assertTrue(CompatChecker.problems("26.3", 25, true, manifestMc("", "", 0, true)).isEmpty());
        // paper servers do not care about the folia claim either way
        assertTrue(CompatChecker.problems("26.3", 25, false, manifestMc("", "", 0, false)).isEmpty());
    }

    @Test
    void unparseableServerVersionIsNotAVerdict() {
        assertTrue(CompatChecker.problems(null, 25, false, manifestMc("1.20", "26.3", 0, false)).isEmpty());
        assertTrue(CompatChecker.problems("", 25, false, manifestMc("1.20", "26.3", 0, false)).isEmpty());
    }
}
