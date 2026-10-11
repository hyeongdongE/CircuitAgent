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
}
