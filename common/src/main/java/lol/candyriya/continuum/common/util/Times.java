package lol.candyriya.continuum.common.util;

import java.time.Duration;

/** Human-friendly duration formatting for status output and logs. */
public final class Times {

    private Times() {
    }

    /**
     * Compact rendering like {@code 1h 30m}, {@code 5m 3s} or {@code 400ms}.
     * Negative durations render with a leading minus. Seconds are always
     * included when non-zero below one hour; milliseconds appear only below
     * one second.
     */
    public static String format(Duration duration) {
        if (duration == null) {
            throw new NullPointerException("duration must not be null");
        }
        long millis = duration.toMillis();
        if (millis < 1000 && millis >= -1000) {
            return millis + "ms";
        }
        long seconds = Math.abs(duration.getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        var sb = new StringBuilder(duration.isNegative() ? "-" : "");
        if (hours > 0) {
            sb.append(hours).append('h');
            if (minutes > 0) {
                sb.append(' ').append(minutes).append('m');
            }
            return sb.toString();
        }
        if (minutes > 0) {
            sb.append(minutes).append('m');
            if (secs > 0) {
                sb.append(' ').append(secs).append('s');
            }
            return sb.toString();
        }
        sb.append(secs).append('s');
        return sb.toString();
    }
}
