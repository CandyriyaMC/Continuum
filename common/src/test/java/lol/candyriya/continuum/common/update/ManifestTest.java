package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestTest {

    private static final String VALID = """
            {
              "schema": 1,
              "channel": "release",
              "build": "build-120",
              "sha256": "abababababababababababababababababababababababababababababababab",
              "file": "release/build-120/continuum-build-120.jar",
              "mc": { "min": "1.20", "max": "26.3" },
              "java": { "min": 17 },
              "folia": true,
              "releasedAt": "2026-11-01T00:00:00.000Z"
            }
            """;

    @Test
    void parsesValidManifest() {
        Manifest m = Manifest.parse(VALID.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, m.schema());
        assertEquals(UpdateChannel.RELEASE, m.channel());
        assertEquals("build-120", m.build());
        assertEquals("abababababababababababababababababababababababababababababababab", m.sha256());
        assertEquals("1.20", m.mcMin());
        assertEquals("26.3", m.mcMax());
        assertEquals(17, m.javaMin());
        assertTrue(m.folia());
    }

    @Test
    void unknownTopLevelKeysAreTolerated() {
        String withExtras = VALID.replace("\"schema\": 1,", "\"schema\": 1, \"commit\": \"abc123\", \"builtAt\": \"yesterday\", \"artifacts\": {\"x\": {\"sha256\": \"y\"}},");
        Manifest m = Manifest.parse(withExtras.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("build-120", m.build());
    }

    @Test
    void schemaTwoIsRefused() {
        String schemaTwo = VALID.replace("\"schema\": 1", "\"schema\": 2");
        assertThrows(Manifest.ManifestException.class,
                () -> Manifest.parse(schemaTwo.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void badSha256IsRefused() {
        String bad = VALID.replace("abababababababababababababababababababababababababababababababab", "nothex");
        assertThrows(Manifest.ManifestException.class,
                () -> Manifest.parse(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void missingBuildIsRefused() {
        String noBuild = VALID.replace("\"build\": \"build-120\",", "");
        assertThrows(Manifest.ManifestException.class,
                () -> Manifest.parse(noBuild.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void devChannelManifestParses() {
        String dev = VALID.replace("\"channel\": \"release\"", "\"channel\": \"dev\"").replace("\"build\": \"build-120\"", "\"build\": \"devbuild-88\"");
        Manifest m = Manifest.parse(dev.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(UpdateChannel.DEV, m.channel());
        assertEquals("devbuild-88", m.build());
    }

    @Test
    void garbageIsRefused() {
        assertThrows(Manifest.ManifestException.class, () -> Manifest.parse("not json".getBytes()));
        assertThrows(Manifest.ManifestException.class, () -> Manifest.parse(new byte[0]));
    }
}
