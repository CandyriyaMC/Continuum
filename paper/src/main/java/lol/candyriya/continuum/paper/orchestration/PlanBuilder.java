package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Builds the deterministic module order used by both capture and restore.
 *
 * <p>Rules, in order of precedence:</p>
 * <ol>
 *   <li>Dependencies first, always. A module never runs before the modules it
 *       declares in {@code dependencies()} while their dependents are in the
 *       plan — configuration may only influence tie-breaking among nodes whose
 *       dependencies are all satisfied.</li>
 *   <li>{@code sync.recovery.restore-order} (when set) is the primary order key
 *       for the modules it names; modules it omits follow, keyed by the built-in
 *       default order; modules known to neither follow by their declared
 *       priority and then id.</li>
 *   <li>Everything is tie-broken by module id, so equal keys still produce one
 *       deterministic order for any given module set.</li>
 * </ol>
 *
 * <p>Global dependency cycles are rejected: the plan cannot be built and the
 * operation fails loudly instead of restoring half a graph in an arbitrary
 * order.</p>
 */
public final class PlanBuilder {

    private PlanBuilder() {
    }

    /**
     * The default capture/restore order, matching the intended login experience
     * (identity/session is handled by the gate and session lifecycle, not by a
     * module): attributes, game mode, inventory, ender chest, vitals, effects,
     * experience, advancements, recipes, statistics, pdc/metadata, custom
     * adapters (when they land), location LAST.
     */
    public static final List<String> DEFAULT_ORDER = List.of(
            BuiltinModules.ATTRIBUTES,
            BuiltinModules.GAMEMODE,
            BuiltinModules.INVENTORY,
            BuiltinModules.ENDERCHEST,
            BuiltinModules.VITALS,
            BuiltinModules.EFFECTS,
            BuiltinModules.EXPERIENCE,
            BuiltinModules.ADVANCEMENTS,
            BuiltinModules.RECIPES,
            BuiltinModules.STATISTICS,
            BuiltinModules.PDC,
            BuiltinModules.METADATA,
            BuiltinModules.PERMISSIONS,
            BuiltinModules.LOCATION);

    /** One participant of a plan: id, declared priority, declared dependency ids. */
    public record Node(String id, int priority, List<String> dependencies) {
        public Node {
            Objects.requireNonNull(id, "id");
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    /** The cycle-reporting exception: names the modules that could not be ordered. */
    public static final class PlanException extends IllegalStateException {

        public PlanException(String message) {
            super(message);
        }
    }

    /**
     * Orders {@code modules} per the rules above.
     *
     * @param modules        the modules to order; duplicate ids are rejected
     * @param configuredOrder optional override, expected in dotted ids (translate the
     *                       config's short ids before calling); entries naming modules
     *                       that are not present are ignored
     * @return the modules in capture/restore order; never null
     * @throws PlanException on dependency cycles or duplicate ids
     */
    public static List<Node> build(List<Node> modules, List<String> configuredOrder) {
        Objects.requireNonNull(modules, "modules");
        List<String> configured = configuredOrder == null ? List.of() : List.copyOf(configuredOrder);

        Map<String, Node> byId = new HashMap<>();
        for (Node node : modules) {
            Node previous = byId.put(node.id(), node);
            if (previous != null) {
                throw new PlanException("duplicate module id in plan: " + node.id());
            }
        }

        Map<String, Long> orderKey = new HashMap<>();
        for (int i = 0; i < configured.size(); i++) {
            orderKey.putIfAbsent(configured.get(i), (long) i);
        }
        int configuredSize = configured.size();
        for (int i = 0; i < DEFAULT_ORDER.size(); i++) {
            orderKey.putIfAbsent(DEFAULT_ORDER.get(i), (long) configuredSize + i);
        }
        long tailBase = configuredSize + (long) DEFAULT_ORDER.size();
        long fallback = tailBase + 100_000L;
        for (Node node : byId.values()) {
            orderKey.putIfAbsent(node.id(), fallback + node.priority());
        }

        // Kahn's algorithm with a priority queue for determinism.
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (Node node : byId.values()) {
            int degree = 0;
            for (String dep : node.dependencies()) {
                if (byId.containsKey(dep)) {
                    degree++;
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.id());
                }
                // a dependency that is not registered is not an ordering constraint here;
                // the executor reports it as DEPENDENCY_MISSING per the module's policy
            }
            indegree.put(node.id(), degree);
        }

        PriorityQueue<Node> ready = new PriorityQueue<>(
                (a, b) -> {
                    int byKey = Long.compare(orderKey.get(a.id()), orderKey.get(b.id()));
                    return byKey != 0 ? byKey : a.id().compareTo(b.id());
                });
        for (Node node : byId.values()) {
            if (indegree.get(node.id()) == 0) {
                ready.add(node);
            }
        }

        List<Node> ordered = new ArrayList<>(byId.size());
        while (!ready.isEmpty()) {
            Node next = ready.poll();
            ordered.add(next);
            for (String dependent : dependents.getOrDefault(next.id(), List.of())) {
                int remaining = indegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(byId.get(dependent));
                }
            }
        }

        if (ordered.size() != byId.size()) {
            Set<String> stuck = new HashSet<>(byId.keySet());
            stuck.removeAll(ordered.stream().map(Node::id).toList());
            List<String> cycle = new ArrayList<>(stuck);
            cycle.sort(String::compareTo);
            throw new PlanException("dependency cycle among modules: " + String.join(", ", cycle));
        }
        return List.copyOf(ordered);
    }
}
