package lol.candyriya.continuum.paper.modules.recipes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeRestoreEngineTest {

    /** In-memory fake of the player book; records every batch call. */
    private static final class FakePlayerOps implements PlayerRecipeOps {
        final Set<String> book = new HashSet<>();
        int discoverCalls;
        int undiscoverCalls;
        List<String> lastDiscoverBatch;
        List<String> lastUndiscoverBatch;
        RuntimeException discoverThrows;
        RuntimeException undiscoverThrows;
        Integer scriptedDiscoverReturn;

        @Override
        public Set<String> discoveredKeys() {
            return Set.copyOf(book);
        }

        @Override
        public int discover(List<String> keys) {
            discoverCalls++;
            lastDiscoverBatch = List.copyOf(keys);
            if (discoverThrows != null) {
                throw discoverThrows;
            }
            if (scriptedDiscoverReturn != null) {
                // report the scripted delta and apply nothing — consistent with a pre-applied book
                return scriptedDiscoverReturn;
            }
            book.addAll(keys);
            return keys.size();
        }

        @Override
        public int undiscover(List<String> keys) {
            undiscoverCalls++;
            lastUndiscoverBatch = List.copyOf(keys);
            if (undiscoverThrows != null) {
                throw undiscoverThrows;
            }
            book.removeAll(keys);
            return keys.size();
        }
    }

    /** In-memory fake of the server recipe map; records refresh calls. */
    private static final class FakeServerLookup implements ServerRecipeLookup {
        final Set<String> known;
        int updateRecipesCalls;

        FakeServerLookup(String... knownKeys) {
            this.known = Set.of(knownKeys);
        }

        @Override
        public boolean hasRecipe(String key) {
            return known.contains(key);
        }

        @Override
        public void updateRecipes() {
            updateRecipesCalls++;
        }
    }

    private static RecipesPayload payload(String... recipes) {
        return new RecipesPayload(RecipesPayload.FORMAT, List.of(recipes));
    }

    @Test
    void discoverAndForgetApplyInSingleBatchesAndRefreshOnce() {
        FakePlayerOps ops = new FakePlayerOps();
        ops.book.add("minecraft:cake"); // locally learned, not captured -> forget
        ops.book.add("minecraft:bread"); // captured and already known -> stays
        FakeServerLookup lookup = new FakeServerLookup("minecraft:bread", "minecraft:cookie", "minecraft:cake");

        RecipeRestoreReport report = RecipeRestoreEngine.restore(
                payload("minecraft:bread", "minecraft:cookie"), ops, lookup);

        assertEquals(List.of("minecraft:cookie"), ops.lastDiscoverBatch);
        assertEquals(List.of("minecraft:cake"), ops.lastUndiscoverBatch);
        assertEquals(1, ops.discoverCalls);
        assertEquals(1, ops.undiscoverCalls);
        assertEquals(Set.of("minecraft:bread", "minecraft:cookie"), ops.book);
        assertEquals(1, lookup.updateRecipesCalls);
        assertEquals(1, report.discovered());
        assertEquals(1, report.undiscovered());
        assertEquals(2, report.mutations());
        assertTrue(report.clientRefresh());
    }

    @Test
    void inSyncBookTriggersNoOpsAndNoRefresh() {
        FakePlayerOps ops = new FakePlayerOps();
        ops.book.add("minecraft:bread");
        FakeServerLookup lookup = new FakeServerLookup("minecraft:bread");

        RecipeRestoreReport report = RecipeRestoreEngine.restore(payload("minecraft:bread"), ops, lookup);

        assertEquals(0, ops.discoverCalls, "discover must not be called for an empty batch");
        assertEquals(0, ops.undiscoverCalls, "undiscover must not be called for an empty batch");
        assertEquals(0, lookup.updateRecipesCalls, "no mutation means no server-wide recipe broadcast");
        assertEquals(0, report.mutations());
        assertFalse(report.clientRefresh());
    }

    @Test
    void unknownCapturedKeysAreSkippedAndNothingIsRefreshedWhenNothingChanges() {
        FakePlayerOps ops = new FakePlayerOps();
        FakeServerLookup lookup = new FakeServerLookup("minecraft:stone"); // technic:* unknown

        RecipeRestoreReport report = RecipeRestoreEngine.restore(
                payload("minecraft:stone", "technic:copper_gear"), ops, lookup);

        assertEquals(Set.of("minecraft:stone"), ops.book);
        assertEquals(1, report.unknownToTarget());
        assertEquals(List.of("minecraft:stone"), ops.lastDiscoverBatch);
        // one real mutation happened (stone) so the book refresh fired
        assertEquals(1, lookup.updateRecipesCalls);
    }

    @Test
    void onlyUnknownCapturedKeysMeanNoMutationAndNoRefresh() {
        FakePlayerOps ops = new FakePlayerOps();
        FakeServerLookup lookup = new FakeServerLookup(); // knows nothing

        RecipeRestoreReport report = RecipeRestoreEngine.restore(
                payload("technic:copper_gear", "technic:tin_ingot"), ops, lookup);

        assertEquals(0, ops.discoverCalls);
        assertEquals(0, lookup.updateRecipesCalls);
        assertEquals(2, report.unknownToTarget());
        assertEquals(Set.of(), ops.book);
    }

    @Test
    void emptyCaptureForgetsEverythingTheTargetKnows() {
        FakePlayerOps ops = new FakePlayerOps();
        ops.book.add("minecraft:cake");
        ops.book.add("minecraft:bed");
        ops.book.add("technic:copper_gear"); // unknown to target -> cannot forget anyway
        FakeServerLookup lookup = new FakeServerLookup("minecraft:cake", "minecraft:bed");

        RecipeRestoreReport report = RecipeRestoreEngine.restore(payload(), ops, lookup);

        assertEquals(List.of("minecraft:bed", "minecraft:cake"), ops.lastUndiscoverBatch);
        assertEquals(Set.of("technic:copper_gear"), ops.book);
        assertEquals(2, report.undiscovered());
        assertEquals(1, report.forgetSkippedUnknown());
        assertEquals(1, lookup.updateRecipesCalls);
    }

    @Test
    void nullPayloadIsRejectedBeforeTouchingAnything() {
        FakePlayerOps ops = new FakePlayerOps();
        ops.book.add("minecraft:cake");
        FakeServerLookup lookup = new FakeServerLookup("minecraft:cake");

        assertThrows(IllegalArgumentException.class, () -> RecipeRestoreEngine.restore(null, ops, lookup));
        assertEquals(Set.of("minecraft:cake"), ops.book, "book must be untouched");
        assertEquals(0, ops.discoverCalls + ops.undiscoverCalls);
        assertEquals(0, lookup.updateRecipesCalls);
    }

    @Test
    void nullRecipesListIsRejectedBeforeTouchingAnything() {
        FakePlayerOps ops = new FakePlayerOps();
        ops.book.add("minecraft:cake");
        FakeServerLookup lookup = new FakeServerLookup("minecraft:cake");
        // corrupt payload: the list field failed to deserialize
        RecipesPayload corrupt = new RecipesPayload(RecipesPayload.FORMAT, null);

        assertThrows(IllegalArgumentException.class, () -> RecipeRestoreEngine.restore(corrupt, ops, lookup));
        assertEquals(Set.of("minecraft:cake"), ops.book, "corrupt payload must never wipe the book");
        assertEquals(0, ops.undiscoverCalls);
        assertEquals(0, lookup.updateRecipesCalls);
    }

    @Test
    void discoverFailurePropagatesAndSkipsRefresh() {
        FakePlayerOps ops = new FakePlayerOps();
        ops.discoverThrows = new IllegalStateException("platform exploded");
        FakeServerLookup lookup = new FakeServerLookup("minecraft:bread");

        assertThrows(IllegalStateException.class,
                () -> RecipeRestoreEngine.restore(payload("minecraft:bread"), ops, lookup));
        assertEquals(0, lookup.updateRecipesCalls);
    }

    @Test
    void undiscoverFailurePropagatesAndSkipsRefresh() {
        FakePlayerOps ops = new FakePlayerOps();
        ops.book.add("minecraft:cake");
        ops.undiscoverThrows = new IllegalStateException("platform exploded");
        FakeServerLookup lookup = new FakeServerLookup("minecraft:cake");

        assertThrows(IllegalStateException.class,
                () -> RecipeRestoreEngine.restore(payload(), ops, lookup));
        assertEquals(0, lookup.updateRecipesCalls);
    }

    @Test
    void batchOrderIsDeterministicAndSorted() {
        FakePlayerOps ops = new FakePlayerOps();
        FakeServerLookup lookup = new FakeServerLookup(
                "minecraft:stone", "minecraft:apple", "minecraft:bread", "minecraft:zoo");
        // deliberately unordered capture
        List<String> unsorted = new ArrayList<>(List.of(
                "minecraft:zoo", "minecraft:stone", "minecraft:apple", "minecraft:bread"));
        Collections.shuffle(unsorted, new java.util.Random(42));

        RecipeRestoreEngine.restore(new RecipesPayload(RecipesPayload.FORMAT, unsorted), ops, lookup);

        assertEquals(List.of("minecraft:apple", "minecraft:bread", "minecraft:stone", "minecraft:zoo"),
                ops.lastDiscoverBatch);
    }

    @Test
    void platformReportedDeltaIsReportedAsIs() {
        // server may report 0 newly applied keys (race with another sync) — refresh still happens once
        FakePlayerOps ops = new FakePlayerOps();
        ops.scriptedDiscoverReturn = 0; // server says: nothing was new (lost a race with another sync)
        FakeServerLookup lookup = new FakeServerLookup("minecraft:bread");

        RecipeRestoreReport report = RecipeRestoreEngine.restore(payload("minecraft:bread"), ops, lookup);

        assertEquals(0, report.discovered());
        assertTrue(report.clientRefresh(), "plan said something had to change, so refresh fires regardless of the reported delta");
        assertEquals(1, lookup.updateRecipesCalls);
    }

    @Test
    void duplicatesInCaptureAreCollapsedAndCounted() {
        FakePlayerOps ops = new FakePlayerOps();
        FakeServerLookup lookup = new FakeServerLookup("minecraft:stone", "minecraft:apple");

        RecipeRestoreReport report = RecipeRestoreEngine.restore(
                payload("minecraft:stone", "minecraft:apple", "minecraft:stone"), ops, lookup);

        assertEquals(1, report.duplicatesCollapsed());
        assertEquals(List.of("minecraft:apple", "minecraft:stone"), ops.lastDiscoverBatch);
    }

    @Test
    void invalidCapturedEntriesAreIgnoredNotRestored() {
        FakePlayerOps ops = new FakePlayerOps();
        List<String> captured = new ArrayList<>();
        captured.add("garbage key");
        captured.add("minecraft:stone");
        FakeServerLookup lookup = new FakeServerLookup("minecraft:stone");

        RecipeRestoreReport report = RecipeRestoreEngine.restore(
                new RecipesPayload(RecipesPayload.FORMAT, captured), ops, lookup);

        assertEquals(1, report.invalidCaptured());
        assertEquals(List.of("minecraft:stone"), ops.lastDiscoverBatch);
        assertEquals(1, lookup.updateRecipesCalls);
    }

    @Test
    void refreshAtMostOnceEvenWithBothHalves() {
        FakePlayerOps ops = new FakePlayerOps();
        ops.book.add("minecraft:cake");
        FakeServerLookup lookup = new FakeServerLookup("minecraft:cake", "minecraft:cookie");

        RecipeRestoreEngine.restore(payload("minecraft:cookie"), ops, lookup);

        assertEquals(1, lookup.updateRecipesCalls);
        assertEquals(1, ops.discoverCalls);
        assertEquals(1, ops.undiscoverCalls);
    }
}
