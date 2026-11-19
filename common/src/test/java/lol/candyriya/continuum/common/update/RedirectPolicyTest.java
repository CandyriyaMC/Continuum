package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedirectPolicyTest {

    private final RedirectPolicy policy = new RedirectPolicy(List.of(".r2.dev"));

    @Test
    void allowsR2Hosts() {
        assertTrue(policy.allows("https://pub-abc123.r2.dev/bucket/key?X-Amz-signature=x", null));
        assertTrue(policy.allows("https://r2.dev/bucket/key", null));
    }

    @Test
    void allowsTheServiceHost() {
        assertTrue(policy.allows("https://upd.candyriya.xsxs.lol/v1/manifest.json", "upd.candyriya.xsxs.lol"));
    }

    @Test
    void refusesEverythingElse() {
        assertFalse(policy.allows("https://evil.example.com/x", "upd.candyriya.xsxs.lol"));
        assertFalse(policy.allows("https://r2.dev.evil.com/x", null));
        assertFalse(policy.allows("https://notevil-r2.dev/x", null));
        assertFalse(policy.allows("https://sneaky.r2.dev.attacker.net/x", null));
    }

    @Test
    void refusesNonHttpsAndCredentialUrls() {
        assertFalse(policy.allows("http://pub-abc123.r2.dev/x", null));
        assertFalse(policy.allows("https://user:pass@pub-abc123.r2.dev/x", null));
        assertFalse(policy.allows("https://pub-abc123.r2.dev:8443/x", null));
        assertFalse(policy.allows("not a url", null));
        assertFalse(policy.allows("https://pub-abc123.r2.dev/x#frag", null));
    }

    @Test
    void defaultListIsR2Dev() {
        RedirectPolicy defaults = new RedirectPolicy(null);
        assertTrue(defaults.allows("https://pub-abc123.r2.dev/x", null));
        assertFalse(defaults.allows("https://cdn.example.com/x", null));
    }
}
