package lol.candyriya.continuum.paper.modules.experience;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordering and value tests for the experience restore path, driven through a
 * recording {@link ExperienceOps} — the production restore executes exactly
 * the same code, only the entity calls are scripted here.
 */
class ExperienceApplyOrderTest {

    private static final class RecordingOps implements ExperienceOps {
        final List<String> calls = new ArrayList<>();

        @Override
        public void setTotalExperience(int total) {
            calls.add("setTotalExperience=" + total);
        }

        @Override
        public void setLevel(int level) {
            calls.add("setLevel=" + level);
        }

        @Override
        public void setExp(float progress) {
            calls.add("setExp=" + progress);
        }
    }

    @Test
    void restoreAppliesTotalThenLevelThenProgress() {
        RecordingOps ops = new RecordingOps();
        ExperienceApply.apply(new ExperiencePayload(ExperiencePayload.FORMAT, 30, 0.42f, 1234), ops);

        assertEquals(List.of("setTotalExperience=1234", "setLevel=30", "setExp=0.42"), ops.calls);
    }

    @Test
    void progressIsWrittenLastAndBitExact() {
        RecordingOps ops = new RecordingOps();
        float progress = 0.42f;
        ExperienceApply.apply(new ExperiencePayload(ExperiencePayload.FORMAT, 30, progress, 1234), ops);

        String last = ops.calls.get(ops.calls.size() - 1);
        assertTrue(last.startsWith("setExp="), "progress must be the last write: " + ops.calls);
        assertEquals(Float.floatToIntBits(progress),
                Float.floatToIntBits(Float.parseFloat(last.substring("setExp=".length()))));
    }

    @Test
    void pendingLevelUpProgressIsPreserved() {
        RecordingOps ops = new RecordingOps();
        ExperienceApply.apply(new ExperiencePayload(ExperiencePayload.FORMAT, 30, 1.0f, 1234), ops);

        assertTrue(ops.calls.contains("setExp=1.0"), ops.calls.toString());
    }

    @Test
    void applyIsIdempotent() {
        RecordingOps ops = new RecordingOps();
        ExperiencePayload payload = new ExperiencePayload(ExperiencePayload.FORMAT, 30, 0.42f, 1234);
        ExperienceApply.apply(payload, ops);
        List<String> first = List.copyOf(ops.calls);
        ops.calls.clear();
        ExperienceApply.apply(payload, ops);

        assertEquals(first, ops.calls);
    }
}
