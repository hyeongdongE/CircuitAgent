package com.circuitagent.design.model;

import java.util.*;

public record CircuitDesign(UUID id, String name, String version,
                            Map<String, ComponentInstance> components,
                            Map<UUID, PartSpecification> specifications,
                            List<ComponentPin> pins, Map<String, CircuitNet> nets,
                            List<PinConnection> connections, List<ImportIssue> issues,
                            Map<String, String> sourceMetadata) {
    public CircuitDesign {
        Objects.requireNonNull(id, "id");
        ModelChecks.required(name, "name");
        ModelChecks.required(version, "version");
        components = Map.copyOf(components);
        specifications = Map.copyOf(specifications);
        pins = List.copyOf(pins);
        nets = Map.copyOf(nets);
        connections = List.copyOf(connections);
        issues = List.copyOf(issues);
        sourceMetadata = Map.copyOf(sourceMetadata);
        specifications.forEach((key, spec) -> {
            if (!key.equals(spec.id())) throw new IllegalArgumentException("Specification ID mismatch");
        });
        for (var entry : components.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().reference())
                || !specifications.containsKey(entry.getValue().partSpecificationId()))
                throw new IllegalArgumentException("Invalid component specification/reference");
        }
        Set<PinKey> knownPins = new HashSet<>();
        for (var pin : pins) {
            if (!components.containsKey(pin.key().reference()) || !knownPins.add(pin.key()))
                throw new IllegalArgumentException("Unknown component or duplicate pin");
        }
        nets.forEach((key, net) -> {
            if (!key.equals(net.id())) throw new IllegalArgumentException("Net ID mismatch");
        });
        Set<PinKey> connected = new HashSet<>();
        for (var connection : connections) {
            if (!knownPins.contains(connection.pin()) || !nets.containsKey(connection.netId())
                || !connected.add(connection.pin()))
                throw new IllegalArgumentException("Dangling or ambiguous connection");
        }
    }
}
