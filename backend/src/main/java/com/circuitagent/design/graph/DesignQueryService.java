package com.circuitagent.design.graph;

import com.circuitagent.design.model.*;
import com.circuitagent.design.persistence.CircuitDesignStore;
import org.springframework.stereotype.Service;
import java.util.*;

/** Stage 03 reads a single immutable snapshot without parsing files or repeated DB loads. */
@Service
public class DesignQueryService {
    private final CircuitDesignStore store;
    public DesignQueryService(CircuitDesignStore store) { this.store = store; }
    public CircuitGraph snapshot(UUID designId) { return new CircuitGraph(store.load(designId)); }
    public ComponentInstance getComponent(UUID id, String ref) { return snapshot(id).getComponent(ref); }
    public List<ComponentPin> getComponentPins(UUID id, String ref) { return snapshot(id).getComponentPins(ref); }
    public Optional<CircuitNet> getNetOfPin(UUID id, String ref, String number) { return snapshot(id).getNetOfPin(ref, number); }
    public List<ComponentPin> getConnectedPins(UUID id, String netId) { return snapshot(id).getConnectedPins(netId); }
    public Set<ComponentInstance> getComponentsOnNet(UUID id, String netId) { return snapshot(id).getComponentsOnNet(netId); }
    public Map<String, AttributeValue> getComponentAttributes(UUID id, String ref) { return snapshot(id).getComponentAttributes(ref); }
}
