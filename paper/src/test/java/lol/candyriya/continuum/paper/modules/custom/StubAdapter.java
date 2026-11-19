package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.module.SyncAdapter;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Test doubles: a fully configurable {@link StubAdapter} and the capture payload type.
 * Test-only behavioral doubles, not stubs — every hook does what it says when told to.
 */
final class StubAdapter implements SyncAdapter<Player, StubAdapter.Payload> {

    /** Capture payload for the test adapter; simple, gson-friendly. */
    record Payload(String value, long count) {
    }

    final String id;
    int version = 1;
    boolean required = true;
    FailurePolicy policy;
    Duration timeout = Duration.ofSeconds(2);
    List<String> dependencies = List.of();
    int priority = SyncModule.DEFAULT_PRIORITY;

    Function<Player, CompletionStage<Payload>> captureFn =
            player -> CompletableFuture.completedFuture(new Payload("captured", 7L));
    Function<Player, CompletionStage<Void>> restoreFn =
            player -> CompletableFuture.completedFuture(null);
    Function<Player, CompletionStage<ValidationResult>> validateFn =
            player -> CompletableFuture.completedFuture(ValidationResult.ok());
    BiFunction<Integer, String, CompletionStage<Payload>> migrateFn;

    /** Every restore invocation, in order: adapter id is constant, data value recorded. */
    final List<Payload> restoredPayloads = new CopyOnWriteArrayList<>();
    /** Every validate invocation, in order. */
    final List<Payload> validatedPayloads = new CopyOnWriteArrayList<>();
    /** Every migrate invocation, in order: (fromVersion, rawPayload). */
    final List<String> migratedFrom = new CopyOnWriteArrayList<>();

    StubAdapter(String id) {
        this.id = id;
        this.migrateFn = (fromVersion, raw) -> CompletableFuture.failedFuture(
                new lol.candyriya.continuum.api.module.AdapterMigrationException(id, fromVersion,
                        "no migration registered"));
    }

    StubAdapter optional() {
        this.required = false;
        return this;
    }

    StubAdapter required(boolean value) {
        this.required = value;
        return this;
    }

    StubAdapter policy(FailurePolicy failurePolicy) {
        this.policy = failurePolicy;
        return this;
    }

    StubAdapter version(int schemaVersion) {
        this.version = schemaVersion;
        return this;
    }

    StubAdapter timeout(Duration duration) {
        this.timeout = duration;
        return this;
    }

    StubAdapter dependencies(List<String> ids) {
        this.dependencies = ids;
        return this;
    }

    StubAdapter priority(int restorePriority) {
        this.priority = restorePriority;
        return this;
    }

    StubAdapter onCapture(Function<Player, CompletionStage<Payload>> fn) {
        this.captureFn = fn;
        return this;
    }

    StubAdapter onRestore(Function<Player, CompletionStage<Void>> fn) {
        this.restoreFn = fn;
        return this;
    }

    StubAdapter onValidate(Function<Player, CompletionStage<ValidationResult>> fn) {
        this.validateFn = fn;
        return this;
    }

    StubAdapter onMigrate(BiFunction<Integer, String, CompletionStage<Payload>> fn) {
        this.migrateFn = fn;
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public boolean required() {
        return required;
    }

    @Override
    public FailurePolicy failurePolicy() {
        return policy != null ? policy : (required ? FailurePolicy.ABORT : FailurePolicy.SKIP);
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public List<String> dependencies() {
        return dependencies;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public CompletionStage<Payload> capture(Player player) {
        return captureFn.apply(player);
    }

    @Override
    public CompletionStage<Void> restore(Player player, Payload data) {
        restoredPayloads.add(data);
        return restoreFn.apply(player);
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, Payload data) {
        validatedPayloads.add(data);
        return validateFn.apply(player);
    }

    @Override
    public CompletionStage<Payload> migrate(int fromVersion, String rawPayload) {
        migratedFrom.add(fromVersion + ":" + rawPayload);
        return migrateFn.apply(fromVersion, rawPayload);
    }
}
