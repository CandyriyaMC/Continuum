package lol.candyriya.continuum.paper.modules.experience;

/**
 * Applies a validated {@link ExperiencePayload} in the documented order:
 *
 * <ol>
 *   <li>{@code setTotalExperience} — pure field write, no packets,</li>
 *   <li>{@code setLevel} — writes the level field (the platform may emit the
 *       experience packet here),</li>
 *   <li>{@code setExp} — writes the in-level progress and arms the forced
 *       resend flag, so the next packet rebuild carries the final
 *       {@code (progress, total, level)} trio exactly as stored.</li>
 * </ol>
 *
 * <p>Why this order: the three fields are independent on the platform side,
 * and only {@code setExp} arms a resend of the client experience packet.
 * Writing it last guarantees the client never renders a progress value that
 * belongs to the pre-restore total. Writing total first is equally safe and
 * keeps the {@code (level, progress, total)} triple consistent before any
 * packet can observe it. Partial progress inside the level is restored
 * verbatim — it is never folded into or derived from the level.</p>
 *
 * <p>All writes are absolute values; re-applying the same payload is
 * idempotent.</p>
 */
final class ExperienceApply {

    private ExperienceApply() {
        // static access only
    }

    static void apply(ExperiencePayload data, ExperienceOps ops) {
        ops.setTotalExperience(ExperienceMath.resolveTotal(data.totalExperience()));
        ops.setLevel(ExperienceMath.resolveLevel(data.level()));
        ops.setExp(ExperienceMath.resolveProgress(data.progress()));
    }
}
