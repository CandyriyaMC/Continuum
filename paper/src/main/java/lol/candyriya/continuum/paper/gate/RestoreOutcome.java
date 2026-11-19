package lol.candyriya.continuum.paper.gate;

/**
 * Result of a gated restore. Exactly one outcome completes the future returned
 * by {@link RestoreGate#enqueueRestore(org.bukkit.entity.Player)}.
 *
 * <p>{@link Ready} means the player got control back. {@link Failed} means the
 * restore did not complete; the player was kicked (or already left) and the
 * failure was recorded against them.</p>
 */
public sealed interface RestoreOutcome {

    /** The restore completed and the player is back in control. */
    record Ready() implements RestoreOutcome {
    }

    /**
     * The restore failed.
     *
     * @param reason short, non-sensitive diagnostic (never shown to the player, never logged with secrets).
     *               Known values: {@code "timeout"}, {@code "quit"}, {@code "handler-failed"},
     *               {@code "superseded"}, {@code "shutdown"}.
     */
    record Failed(String reason) implements RestoreOutcome {

        public Failed {
            if (reason == null || reason.isBlank()) {
                reason = "unknown";
            }
        }
    }
}
