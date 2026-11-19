package lol.candyriya.continuum.common.update;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side compatibility verdict for a manifest, checked before anything
 * is downloaded or staged.
 *
 * <p>Rules, all inclusive: {@code mc.min}/{@code mc.max} bound the
 * Minecraft version (numeric segment compare, so {@code 1.9} < {@code 1.21}),
 * {@code java.min} bounds the Java major version, and a Folia server refuses
 * a build whose manifest does not claim Folia support. An unparseable server
 * version is not a verdict of either sign - it falls through to "compatible"
 * and the server-side gate (a 409 from the service) stays the authority.</p>
 */
public final class CompatChecker {

    private static final Pattern NUMERIC = Pattern.compile("(\\d+)");

    private CompatChecker() {
    }

    /**
     * @return human-readable reasons the server must not take this update; empty when compatible
     */
    public static List<String> problems(String serverMc, int javaMajor, boolean serverFolia, Manifest manifest) {
        List<String> problems = new ArrayList<>();
        if (manifest.javaMin() > 0 && javaMajor > 0 && javaMajor < manifest.javaMin()) {
            problems.add("build requires Java >= " + manifest.javaMin() + " (this server reports Java " + javaMajor + ")");
        }
        if (!manifest.mcMin().isBlank()) {
            Integer cmp = compareVersions(serverMc, manifest.mcMin());
            if (cmp != null && cmp < 0) {
                problems.add("build requires Minecraft >= " + manifest.mcMin() + " (this server runs " + serverMc + ")");
            }
        }
        if (!manifest.mcMax().isBlank()) {
            Integer cmp = compareVersions(serverMc, manifest.mcMax());
            if (cmp != null && cmp > 0) {
                problems.add("build does not support Minecraft > " + manifest.mcMax() + " (this server runs " + serverMc + ")");
            }
        }
        if (serverFolia && !manifest.folia()) {
            problems.add("build does not declare Folia support");
        }
        return problems;
    }

    /**
     * Numeric-segment compare: {@code 1.21.4} vs {@code 26.3}. Missing
     * segments count as zero. Returns null when either side has no digits.
     */
    public static Integer compareVersions(String left, String right) {
        if (left == null || right == null) {
            return null;
        }
        List<Integer> a = segments(left);
        List<Integer> b = segments(right);
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        int width = Math.max(a.size(), b.size());
        for (int i = 0; i < width; i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static List<Integer> segments(String version) {
        List<Integer> out = new ArrayList<>();
        Matcher matcher = NUMERIC.matcher(version);
        while (matcher.find()) {
            try {
                out.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return out;
    }
}
