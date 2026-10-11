package com.circuitagent.design.graph;

import com.circuitagent.design.model.*;
import java.util.*;

/** Indexed undirected incidence graph. A component never unions its adjacent Nets. */
public final class CircuitGraph {
    private final CircuitDesign design;
    private final Map<PinKey, ComponentPin> pins = new HashMap<>();
    private final Map<String, List<ComponentPin>> byComponent = new HashMap<>();
    private final Map<PinKey, String> netOfPin = new HashMap<>();
    private final Map<String, List<ComponentPin>> byNet = new HashMap<>();
    public CircuitGraph(CircuitDesign design) {
        this.design = design;
        for (var pin : design.pins()) {
            pins.put(pin.key(), pin);
            byComponent.computeIfAbsent(pin.key().reference(), key -> new ArrayList<>()).add(pin);
        }
        for (var connection : design.connections()) {
            netOfPin.put(connection.pin(), connection.netId());
            byNet.computeIfAbsent(connection.netId(), key -> new ArrayList<>()).add(pins.get(connection.pin()));
        }
    }
    public ComponentInstance getComponent(String ref) {
        var component = design.components().get(ref);
        if (component == null) throw new NoSuchElementException("Component not found: " + ref);
        return component;
    }
    public List<ComponentPin> getComponentPins(String ref) {
        getComponent(ref);
        return List.copyOf(byComponent.getOrDefault(ref, List.of()));
    }
    public Optional<CircuitNet> getNetOfPin(String ref, String number) {
        var key = new PinKey(ref, number);
        if (!pins.containsKey(key)) throw new NoSuchElementException("Pin not found: " + ref + "/" + number);
        return Optional.ofNullable(netOfPin.get(key)).map(design.nets()::get);
    }
    public List<ComponentPin> getConnectedPins(String netId) {
        if (!design.nets().containsKey(netId)) throw new NoSuchElementException("Net not found: " + netId);
        return List.copyOf(byNet.getOrDefault(netId, List.of()));
    }
    public Set<ComponentInstance> getComponentsOnNet(String netId) {
        var result = new LinkedHashSet<ComponentInstance>();
        for (var pin : getConnectedPins(netId)) result.add(getComponent(pin.key().reference()));
        return Collections.unmodifiableSet(result);
    }
    public PartSpecification getComponentSpecification(String ref) {
        return design.specifications().get(getComponent(ref).partSpecificationId());
    }
    public Map<String, AttributeValue> getComponentAttributes(String ref) {
        return getComponentSpecification(ref).attributes();
    }
    public UUID designId() { return design.id(); }
    public List<ImportIssue> getComponentIssues(String ref) {
        getComponent(ref);
        return design.issues().stream().filter(i -> i.reference() == null || ref.equals(i.reference())).toList();
    }
    /** Pin names need not be unique (e.g. several GND pins); return every exact match. */
    public List<ComponentPin> getPinsByName(String ref, String name) {
        Objects.requireNonNull(name, "name");
        return getComponentPins(ref).stream().filter(p -> name.equals(p.name())).toList();
    }
    public enum ConnectionStatus { CONNECTED, UNCONNECTED, TERMINATED, AMBIGUOUS, UNRESOLVED, MISSING_PIN }
    public record PinContext(PinKey key, ComponentPin pin, ConnectionStatus status, CircuitNet net,
                             ComponentInstance component, PartSpecification specification, List<ImportIssue> issues) {}
    public PinContext getPinContext(String ref, String number) {
        var component = getComponent(ref);
        var key = new PinKey(ref, number);
        var pin = pins.get(key);
        var net = Optional.ofNullable(netOfPin.get(key)).map(design.nets()::get).orElse(null);
        boolean ambiguous = design.issues().stream().anyMatch(i -> i.code() == ImportIssue.Code.AMBIGUOUS_CONNECTION
            && ref.equals(i.reference()) && (("pin:" + number).equals(i.source())
                || i.message().startsWith("Pin " + number + " assigned to multiple Nets")));
        boolean inventoryKnown = "complete".equals(component.rawSources().getOrDefault("netlist", Map.of()).get("pinInventory"));
        ConnectionStatus status = pin == null ? ConnectionStatus.MISSING_PIN : ambiguous ? ConnectionStatus.AMBIGUOUS
            : net != null ? ConnectionStatus.CONNECTED : pin.disposition() == PinDisposition.TERMINATED ? ConnectionStatus.TERMINATED
            : pin.disposition() == PinDisposition.UNCONNECTED || inventoryKnown ? ConnectionStatus.UNCONNECTED : ConnectionStatus.UNRESOLVED;
        return new PinContext(key, pin, status, net, component, getComponentSpecification(ref), getComponentIssues(ref));
    }
    public enum EdgeKind { NET, COMPONENT }
    public record TraversalEdge(PinKey from, PinKey to, EdgeKind kind, String netId, String sourcePath) {
        public TraversalEdge(PinKey from, PinKey to, EdgeKind kind, String netId) { this(from, to, kind, netId, null); }
    }
    public enum TraversalMode { EXCLUDE_DNP, INCLUDE_DNP_SCHEMATIC }
    public record Neighborhood(Set<PinKey> pins, Set<String> nets, Set<TraversalEdge> edges) {}
    /**
     * Bounded topology exploration. Component crossings require an explicit caller allow-list.
     * They express topology only, not an electrical short or a conducting-state assertion.
     * DNP components cannot be crossed; no automatic traversal through ICs or all two-pin parts.
     */
    public Neighborhood explore(PinKey start, Set<String> passThroughReferences, int maxSteps) {
        return explore(start, passThroughReferences, maxSteps, TraversalMode.EXCLUDE_DNP);
    }
    public Neighborhood explore(PinKey start, Set<String> passThroughReferences, int maxSteps, TraversalMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (maxSteps < 0 || maxSteps > 32) throw new IllegalArgumentException("maxSteps must be between 0 and 32");
        getNetOfPin(start.reference(), start.number()); // validate start; unconnected pins are valid
        passThroughReferences.forEach(this::getComponent);
        var reached = new LinkedHashSet<PinKey>();
        var reachedNets = new LinkedHashSet<String>();
        var edges = new LinkedHashSet<TraversalEdge>();
        record Visit(PinKey pin, int depth) {}
        var queue = new ArrayDeque<Visit>();
        reached.add(start); queue.add(new Visit(start, 0));
        while (!queue.isEmpty()) {
            var visit = queue.remove();
            String netId = netOfPin.get(visit.pin());
            if (netId != null) reachedNets.add(netId);
            if (visit.depth() == maxSteps) continue;
            var next = new ArrayList<TraversalEdge>();
            if (netId != null) for (var pin : getConnectedPins(netId))
                if (!pin.key().equals(visit.pin())) next.add(edge(visit.pin(), pin.key(), EdgeKind.NET, netId));
            String ref = visit.pin().reference();
            if (passThroughReferences.contains(ref) && (mode == TraversalMode.INCLUDE_DNP_SCHEMATIC || getComponent(ref).assemblyStatus() != AssemblyStatus.DNP))
                for (var pin : getComponentPins(ref)) if (!pin.key().equals(visit.pin()))
                    next.add(edge(visit.pin(), pin.key(), EdgeKind.COMPONENT, null));
            for (var e : next) {
                edges.add(e);
                var destination = e.from().equals(visit.pin()) ? e.to() : e.from();
                if (reached.add(destination)) queue.add(new Visit(destination, visit.depth() + 1));
                if (reached.size() > 10_000 || edges.size() > 50_000)
                    throw new IllegalArgumentException("Traversal budget exceeded; reduce maxSteps/allow-list");
            }
        }
        return new Neighborhood(Set.copyOf(reached), Set.copyOf(reachedNets), Set.copyOf(edges));
    }
    private TraversalEdge edge(PinKey a, PinKey b, EdgeKind kind, String netId) {
        int order = Comparator.comparing(PinKey::reference).thenComparing(PinKey::number).compare(a, b);
        String source = kind == EdgeKind.NET ? design.nets().get(netId).sourcePath()
            : getComponent(a.reference()).rawSources().getOrDefault("provenance", Map.of()).getOrDefault("bomLocation", getComponent(a.reference()).sourcePath());
        return order <= 0 ? new TraversalEdge(a, b, kind, netId, source) : new TraversalEdge(b, a, kind, netId, source);
    }

}
