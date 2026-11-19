package lol.candyriya.continuum.paper.modules.experience;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs experience level, progress inside the level and total experience
 * points.
 *
 * <p><b>Why all three fields.</b> A level alone is lossy: everything between
 * level N and N+1 evaporates. {@code progress} carries the fractional part;
 * {@code total_experience} is the cumulative counter (mending, /xp). The
 * stored triple is applied verbatim via {@code setTotalExperience} →
 * {@code setLevel} → {@code setExp} — see {@link ExperienceApply} for the
 * reasoning behind the order and for the note on why the platform's combined
 * setter is deliberately not used.</p>
 *
 * <p><b>Invariants.</b> Restoring a payload never loses partial progress:
 * the captured progress is written bit-exact (validation guarantees it is
 * inside 0.0..1.0), and no write derives, rounds or normalizes it. Level
 * restore is therefore lossless with respect to the fractional part.</p>
 *
 * <p><b>Dependencies.</b> None — experience has no ordering constraints with
 * other modules.</p>
 *
 * <p>Thread-safety: read/written on the player's own thread; this class keeps
 * no mutable state.</p>
 */
public final class ExperienceModule implements SyncModule<Player, ExperiencePayload> {

    @Override
    public String id() {
        return BuiltinModules.EXPERIENCE;
    }

    @Override
    public int version() {
        return ExperiencePayload.FORMAT;
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public CompletionStage<ExperiencePayload> capture(Player player) {
        return CompletableFuture.completedFuture(new ExperiencePayload(
                ExperiencePayload.FORMAT,
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience()));
    }

    @Override
    public CompletionStage<Void> restore(Player player, ExperiencePayload data) {
        ExperienceApply.apply(data, new PlayerExperienceOps(player));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, ExperiencePayload data) {
        return CompletableFuture.completedFuture(ExperienceValidation.validate(data));
    }

    /** Thin pass-through from the pure restore logic to the live entity. */
    private record PlayerExperienceOps(Player player) implements ExperienceOps {

        @Override
        public void setTotalExperience(int total) {
            player.setTotalExperience(total);
        }

        @Override
        public void setLevel(int level) {
            player.setLevel(level);
        }

        @Override
        public void setExp(float progress) {
            player.setExp(progress);
        }
    }
}
