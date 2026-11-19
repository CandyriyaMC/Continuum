package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.DuplicateModuleIdException;
import lol.candyriya.continuum.api.module.InvalidModuleDefinitionException;
import lol.candyriya.continuum.api.module.ModuleContracts;
import lol.candyriya.continuum.api.module.ModuleInfo;
import lol.candyriya.continuum.api.module.ModuleRegistration;
import lol.candyriya.continuum.api.module.SyncAdapter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend-local registry of third-party {@link SyncAdapter custom adapters}. This is the
 * engine behind the plugin API's registration entry point: the platform's API entry point
 * delegates to it; nothing else should construct or mutate it.
 *
 * <p>Validation reuses the API's {@link ModuleContracts#validate(SyncAdapter)} verbatim —
 * id format, reserved {@code continuum.*} namespace, schema version, timeout, dependency
 * well-formedness, self-dependencies and duplicates — instead of re-implementing it here.
 * Uniqueness is a registry concern (the API contract says so explicitly) and is enforced
 * here, like the cycle check, the reserved-namespace behaviour and the payload-type
 * resolution — all of which the API contract leaves to the implementation.</p>
 *
 * <p><b>Cycle detection</b> runs over the whole graph — adapters <em>and</em> the
 * first-party modules passed in at construction — at every registration, so
 * A→B→C→A can never exist whether it spans adapters, first-party modules or both.
 * Dependencies on unregistered modules are legal at registration time (they report
 * {@code DEPENDENCY_MISSING} at restore), and can close a cycle when they appear later —
 * that registration is then rejected. Unregistering can never create a cycle.</p>
 *
 * <p><b>Thread-safety.</b> All methods are safe from any thread. Mutations and planning
 * snapshots are serialized through one lock; an in-flight operation keeps the (immutable)
 * snapshot taken when it started, so an unregistration never lands underneath a live
 * capture or restore.</p>
 *
 * @since 1.0.0
 */
public final class AdapterRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdapterRegistry.class);

    private final Object lock = new Object();
    private final Map<String, RegisteredAdapter> adapters = new LinkedHashMap<>();
    private final Map<String, FirstPartyModule> externals = new LinkedHashMap<>();

    /** Registry with no first-party context: adapter cycle detection works, cross-namespace ordering declarations stay empty. */
    public AdapterRegistry() {
        this(List.of());
    }

    /**
     * Registry with first-party context, e.g. the modules the platform actually registered.
     * Duplicate external ids are a composition bug and are rejected loudly.
     */
    public AdapterRegistry(List<FirstPartyModule> firstPartyModules) {
        Objects.requireNonNull(firstPartyModules, "firstPartyModules must not be null");
        for (FirstPartyModule external : firstPartyModules) {
            Objects.requireNonNull(external, "firstPartyModules must not contain null elements");
            if (externals.put(external.id(), external) != null) {
                throw new IllegalArgumentException("duplicate first-party module '" + external.id() + "'");
            }
        }
    }

    /**
     * Registers a third-party adapter.
     *
     * @param adapter the adapter to register, never null
     * @param source  where the adapter comes from (usually the owning plugin's name); never null or blank
     * @return the registration handle, never null
     * @throws NullPointerException when {@code adapter} is null
     * @throws IllegalArgumentException when {@code source} is null or blank
     * @throws lol.candyriya.continuum.api.module.InvalidModuleIdException when the id is malformed
     *         or in the reserved {@code continuum.*} namespace
     * @throws DuplicateModuleIdException when the id is already registered
     * @throws InvalidModuleDefinitionException when the contract is inconsistent, the payload
     *         type cannot be resolved, or the registration would create a dependency cycle
     */
    public @NotNull ModuleRegistration register(@NotNull SyncAdapter<?, ?> adapter, @NotNull String source) {
        Objects.requireNonNull(adapter, "adapter must not be null");
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be null or blank");
        }
        // the API's own contract checks: id format, reserved namespace, schema version,
        // timeout, dependency well-formedness/self/duplicates — reused, not reinvented
        ModuleContracts.validate(adapter);

        Type payloadType = resolvePayloadType(adapter.getClass());
        if (payloadType == null || payloadType instanceof TypeVariable<?> || payloadType == Object.class) {
            throw new InvalidModuleDefinitionException(
                    "module '" + adapter.id() + "': could not resolve the payload type T from the adapter class "
                            + "hierarchy; a raw or type-variable-erased adapter cannot roundtrip its data, "
                            + "implement SyncAdapter with a concrete payload type");
        }

        synchronized (lock) {
            if (adapters.containsKey(adapter.id())) {
                throw new DuplicateModuleIdException(adapter.id());
            }
            ensureAcyclic(adapter.id(), adapter.dependencies());
            ModuleInfo info = ModuleInfo.of(adapter, source);
            Registration handle = new Registration(this, adapter.id());
            adapters.put(adapter.id(), new RegisteredAdapter(adapter, payloadType, info, handle));
            LOGGER.debug("registered custom adapter '{}' (source '{}', priority {})", adapter.id(), source, info.priority());
            return handle;
        }
    }

    /** The module metadata of all registered adapters, in restore planning order. Immutable snapshot. */
    public @NotNull List<ModuleInfo> modules() {
        synchronized (lock) {
            return plannedAdaptersLocked().stream().map(RegisteredAdapter::moduleInfo).toList();
        }
    }

    /** Looks up a registered adapter's metadata by id. */
    public @NotNull Optional<ModuleInfo> module(@NotNull String id) {
        Objects.requireNonNull(id, "id must not be null");
        synchronized (lock) {
            RegisteredAdapter found = adapters.get(id);
            return found == null ? Optional.empty() : Optional.of(found.moduleInfo());
        }
    }

    /** Whether an adapter with the id is currently registered. */
    public boolean isRegistered(@NotNull String id) {
        Objects.requireNonNull(id, "id must not be null");
        synchronized (lock) {
            return adapters.containsKey(id);
        }
    }

    /** How many adapters are currently registered. */
    public int registeredCount() {
        synchronized (lock) {
            return adapters.size();
        }
    }

    /** The first-party module ids this registry was constructed with. Immutable snapshot. */
    Set<String> knownFirstPartyIds() {
        synchronized (lock) {
            return Set.copyOf(externals.keySet());
        }
    }

    /** The registered adapters in restore planning order. Immutable snapshot for in-flight operations. */
    List<RegisteredAdapter> plannedAdapters() {
        synchronized (lock) {
            return plannedAdaptersLocked();
        }
    }

    /** Looks up the full registration by adapter id. */
    Optional<RegisteredAdapter> find(String id) {
        synchronized (lock) {
            return Optional.ofNullable(adapters.get(id));
        }
    }

    boolean remove(String id, Registration self) {
        synchronized (lock) {
            RegisteredAdapter current = adapters.get(id);
            if (current != null && current.registration() == self) {
                adapters.remove(id);
                LOGGER.debug("unregistered custom adapter '{}'", id);
                return true;
            }
            return false;
        }
    }

    /** The planning order: topological (dependencies first), then priority ascending, ties by id. */
    private List<RegisteredAdapter> plannedAdaptersLocked() {
        // kahn's algorithm over the adapter subgraph; adapter→first-party edges are ordering
        // constraints the platform applies at pipeline level, not in-module ordering constraints
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (RegisteredAdapter adapter : adapters.values()) {
            indegree.put(adapter.id(), 0);
        }
        for (RegisteredAdapter adapter : adapters.values()) {
            for (String dependency : adapter.moduleInfo().dependencies()) {
                if (adapters.containsKey(dependency)) {
                    indegree.merge(adapter.id(), 1, Integer::sum);
                    dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(adapter.id());
                }
            }
        }
        var ready = new TreeSet<>(Comparator
                .comparingInt((RegisteredAdapter it) -> it.moduleInfo().priority())
                .thenComparing(RegisteredAdapter::id));
        for (RegisteredAdapter adapter : adapters.values()) {
            if (indegree.get(adapter.id()) == 0) {
                ready.add(adapter);
            }
        }
        List<RegisteredAdapter> out = new ArrayList<>(adapters.size());
        while (!ready.isEmpty()) {
            RegisteredAdapter next = ready.pollFirst();
            out.add(next);
            for (String dependent : dependents.getOrDefault(next.id(), List.of())) {
                Integer remaining = indegree.merge(dependent, -1, Integer::sum);
                if (remaining != null && remaining == 0) {
                    ready.add(adapters.get(dependent));
                }
            }
        }
        if (out.size() != adapters.size()) {
            // registration rejects cycles, so this should never happen; stay alive anyway
            LOGGER.warn("inconsistent adapter dependency graph detected at planning time; "
                    + "falling back to priority order for the remainder");
            for (RegisteredAdapter adapter : adapters.values()) {
                if (!out.contains(adapter)) {
                    out.add(adapter);
                }
            }
        }
        return List.copyOf(out);
    }

    /** Rejects a registration that would close a dependency cycle over adapters + first-party modules. */
    private void ensureAcyclic(@NotNull String newId, @NotNull List<String> newDependencies) {
        for (String dependency : newDependencies) {
            if (reaches(dependency, newId, new HashSet<>())) {
                throw new InvalidModuleDefinitionException(
                        "module '" + newId + "': registering would create a dependency cycle through '" + dependency + "'");
            }
        }
    }

    /** Whether {@code from} can reach {@code target} following declared dependency edges. */
    private boolean reaches(@NotNull String from, @NotNull String target, @NotNull Set<String> seen) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (current.equals(target)) {
                return true;
            }
            if (!seen.add(current)) {
                continue;
            }
            for (String dependency : dependenciesOf(current)) {
                stack.push(dependency);
            }
        }
        return false;
    }

    private List<String> dependenciesOf(String id) {
        RegisteredAdapter adapter = adapters.get(id);
        if (adapter != null) {
            return adapter.moduleInfo().dependencies();
        }
        FirstPartyModule external = externals.get(id);
        if (external != null) {
            return external.dependencies();
        }
        // unregistered dependency: no outgoing edges to follow
        return List.of();
    }

    /**
     * Resolves the adapter's payload type T from the class hierarchy by walking interfaces and
     * the superclass chain with a type-variable environment. Returns null when T cannot be
     * resolved to anything concrete.
     */
    @Nullable
    static Type resolvePayloadType(Class<?> adapterClass) {
        record Frame(Type type, Map<TypeVariable<?>, Type> env) {
        }
        Deque<Frame> queue = new ArrayDeque<>();
        queue.add(new Frame(adapterClass, Map.of()));
        Set<Type> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Frame frame = queue.poll();
            if (!visited.add(frame.type())) {
                continue;
            }
            if (frame.type() instanceof Class<?> raw) {
                for (Type iface : raw.getGenericInterfaces()) {
                    queue.add(new Frame(iface, frame.env()));
                }
                Type superclass = raw.getGenericSuperclass();
                if (superclass != null) {
                    queue.add(new Frame(superclass, frame.env()));
                }
                continue;
            }
            if (frame.type() instanceof ParameterizedType parameterized) {
                Class<?> raw = (Class<?>) parameterized.getRawType();
                Map<TypeVariable<?>, Type> env = new HashMap<>(frame.env());
                TypeVariable<?>[] variables = raw.getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < variables.length; i++) {
                    env.put(variables[i], resolveTypeVariable(arguments[i], frame.env()));
                }
                if (raw == SyncAdapter.class) {
                    // SyncAdapter<P, T> — T sits at index 1
                    return resolveTypeVariable(arguments[1], env);
                }
                for (Type iface : raw.getGenericInterfaces()) {
                    queue.add(new Frame(iface, env));
                }
                Type superclass = raw.getGenericSuperclass();
                if (superclass != null) {
                    queue.add(new Frame(superclass, env));
                }
            }
            // GenericArrayType / WildcardType in a payload slot means something very unusual;
            // it simply never matches SyncAdapter and is skipped
        }
        return null;
    }

    private static Type resolveTypeVariable(Type type, Map<TypeVariable<?>, Type> env) {
        if (type instanceof TypeVariable<?> variable) {
            Type bound = env.get(variable);
            return bound == null ? variable : resolveTypeVariable(bound, env);
        }
        return type;
    }

    /** Registration handle: idempotent, thread-safe, cancels the registration atomically. */
    public static final class Registration implements ModuleRegistration {

        private final AdapterRegistry registry;
        private final String id;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(AdapterRegistry registry, String id) {
            this.registry = registry;
            this.id = id;
        }

        @Override
        public @NotNull String id() {
            return id;
        }

        @Override
        public void unregister() {
            // only the first call removes; identity check inside remove() protects a re-registered id
            if (active.compareAndSet(true, false)) {
                registry.remove(id, this);
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }
}
