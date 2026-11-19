package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.module.SyncAdapter;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.paper.modules.advancements.AdvancementsModule;
import lol.candyriya.continuum.paper.modules.advancements.AdvancementsPayload;
import lol.candyriya.continuum.paper.modules.attributes.AttributesModule;
import lol.candyriya.continuum.paper.modules.effects.EffectsModule;
import lol.candyriya.continuum.paper.modules.effects.EffectsPayload;
import lol.candyriya.continuum.paper.modules.experience.ExperienceModule;
import lol.candyriya.continuum.paper.modules.experience.ExperiencePayload;
import lol.candyriya.continuum.paper.modules.gamemode.GameModeModule;
import lol.candyriya.continuum.paper.modules.gamemode.GameModePayload;
import lol.candyriya.continuum.paper.modules.inventory.EnderChestModule;
import lol.candyriya.continuum.paper.modules.inventory.EnderChestPayload;
import lol.candyriya.continuum.paper.modules.inventory.InventoryModule;
import lol.candyriya.continuum.paper.modules.inventory.InventoryPayload;
import lol.candyriya.continuum.paper.modules.location.LocationModule;
import lol.candyriya.continuum.paper.modules.location.LocationPayload;
import lol.candyriya.continuum.paper.modules.recipes.RecipesModule;
import lol.candyriya.continuum.paper.modules.recipes.RecipesPayload;
import lol.candyriya.continuum.paper.modules.statistics.StatisticsModule;
import lol.candyriya.continuum.paper.modules.statistics.StatisticsPayload;
import lol.candyriya.continuum.paper.modules.vitals.VitalsModule;
import lol.candyriya.continuum.paper.modules.vitals.VitalsPayload;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The backend's module registry: what is registered, what is enabled by config,
 * and in which order the modules run.
 *
 * <p>Registration is explicit and first-party only for now: every merged module
 * is listed in {@link #builtins(ContinuumConfig)} with its payload type, so the
 * capture path never has to guess generic type parameters. Registration order
 * is irrelevant — the plan order is built from {@link PlanBuilder}.</p>
 *
 * <p>Failure policy is assigned per first-party module: load-bearing modules
 * (attributes, inventory, vitals) abort the operation on failure; everything
 * else is isolated. Third-party adapters keep their own declared policy.</p>
 *
 * <p>Enablement comes from {@code sync.modules.*} in the shared config (short
 * ids, via {@link ModuleKeys}); modules whose toggle is absent default to
 * enabled, matching the loader's default-filling behaviour. The shared config
 * rejects unknown module ids at load time, so a toggle this registry cannot
 * map is dropped with a debug log rather than crashing the backend.</p>
 */
public final class ModuleRuntime {

    /** First-party modules whose failure aborts the whole operation (load-bearing state). */
    static final Set<String> REQUIRED = Set.of(
            BuiltinModules.ATTRIBUTES,
            BuiltinModules.INVENTORY,
            BuiltinModules.VITALS);

    /** One registered module with everything the pipeline needs around it. */
    public record RegisteredModule(String id,
                                   SyncModule<Player, ?> module,
                                   Class<?> payloadType,
                                   FailurePolicy failurePolicy,
                                   boolean enabled) {

        public RegisteredModule {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(payloadType, "payloadType");
            Objects.requireNonNull(failurePolicy, "failurePolicy");
        }

        /** The per-operation timeout: adapter-declared when applicable, else the caller's default. */
        public long timeoutMillis(long fallbackMillis) {
            if (module instanceof SyncAdapter<?, ?> adapter) {
                return adapter.timeout().toMillis();
            }
            return fallbackMillis;
        }

        /** The module viewed as {@code SyncModule<Player, Object>}; used to call the typed lifecycle with a decoded payload. */
        @SuppressWarnings("unchecked")
        public SyncModule<Player, Object> moduleAs() {
            return (SyncModule<Player, Object>) (SyncModule<Player, ?>) module;
        }
    }

    private final List<RegisteredModule> all;
    private final List<RegisteredModule> planOrder;

    private ModuleRuntime(List<RegisteredModule> all, List<String> configuredOrderDotted) {
        this.all = List.copyOf(all);
        List<PlanBuilder.Node> nodes = all.stream()
                .filter(RegisteredModule::enabled)
                .map(m -> new PlanBuilder.Node(m.id(), m.module().priority(), m.module().dependencies()))
                .toList();
        this.planOrder = PlanBuilder.build(nodes, configuredOrderDotted).stream()
                .map(node -> byIdOrThrow(node.id(), all))
                .toList();
    }

    /**
     * The production registry: every first-party module this build knows,
     * honoring the config toggles and (when set) {@code sync.recovery.restore-order}.
     */
    public static ModuleRuntime builtins(ContinuumConfig config) {
        Objects.requireNonNull(config, "config");
        Map<String, Boolean> toggles = config.sync().modulesWithDefaults();
        List<Spec> specs = new ArrayList<>();
        specs.add(new Spec(new InventoryModule(), InventoryPayload.class));
        specs.add(new Spec(new EnderChestModule(), EnderChestPayload.class));
        specs.add(new Spec(new VitalsModule(), VitalsPayload.class));
        specs.add(new Spec(new ExperienceModule(), ExperiencePayload.class));
        specs.add(new Spec(new EffectsModule(), EffectsPayload.class));
        specs.add(new Spec(new AttributesModule(), Map.class));
        specs.add(new Spec(new GameModeModule(), GameModePayload.class));
        specs.add(new Spec(new AdvancementsModule(), AdvancementsPayload.class));
        specs.add(new Spec(new StatisticsModule(), StatisticsPayload.class));
        specs.add(new Spec(new RecipesModule(), RecipesPayload.class));
        specs.add(new Spec(new LocationModule(), LocationPayload.class));

        List<RegisteredModule> modules = new ArrayList<>(specs.size());
        for (Spec spec : specs) {
            String shortId = ModuleKeys.toShort(spec.module().id());
            boolean enabled = shortId == null || toggles.getOrDefault(shortId, true);
            FailurePolicy policy = REQUIRED.contains(spec.module().id()) ? FailurePolicy.ABORT : FailurePolicy.SKIP;
            modules.add(new RegisteredModule(spec.module().id(), spec.module(), spec.payloadType(), policy, enabled));
        }
        List<String> configured = ModuleKeys.configuredOrderToDotted(config.sync().recovery().restoreOrder());
        return new ModuleRuntime(modules, configured);
    }

    /** Test-only registry assembly with explicit modules and order configuration. Same-module scope. */
    static ModuleRuntime of(List<RegisteredModule> modules, List<String> configuredOrderDotted) {
        return new ModuleRuntime(modules, configuredOrderDotted == null ? List.of() : configuredOrderDotted);
    }

    /** Every registered module, enabled or not. Diagnostics and listing. */
    public List<RegisteredModule> all() {
        return all;
    }

    /** The enabled modules in plan order. This is the capture/restore order. */
    public List<RegisteredModule> planOrder() {
        return planOrder;
    }

    /** One module by dotted id, or null. */
    public RegisteredModule byId(String id) {
        for (RegisteredModule registered : all) {
            if (registered.id().equals(Objects.requireNonNull(id, "id"))) {
                return registered;
            }
        }
        return null;
    }

    /** Whether a module with this dotted id is registered and enabled. */
    public boolean isEnabled(String dottedId) {
        RegisteredModule registered = byId(dottedId);
        return registered != null && registered.enabled();
    }

    /** Ids of everything enabled, in plan order; handy for the dirty tracker. */
    public List<String> enabledIds() {
        return planOrder.stream().map(RegisteredModule::id).toList();
    }

    private static RegisteredModule byIdOrThrow(String id, List<RegisteredModule> modules) {
        for (RegisteredModule registered : modules) {
            if (registered.id().equals(id)) {
                return registered;
            }
        }
        throw new IllegalStateException("module in plan is not registered: " + id);
    }

    private record Spec(SyncModule<Player, ?> module, Class<?> payloadType) {
        private Spec {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(payloadType, "payloadType");
        }
    }
}
