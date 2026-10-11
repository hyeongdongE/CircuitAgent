package com.circuitagent.design.parse;

import com.circuitagent.design.model.*;
import java.util.*;

public record NetlistResult(Map<String, ComponentInstance> components,
                            Map<UUID, PartSpecification> specifications,
                            List<ComponentPin> pins, Map<String, CircuitNet> nets,
                            List<PinConnection> connections, List<ImportIssue> issues,
                            Map<String, String> sourceMetadata) {
    public NetlistResult {
        components = Map.copyOf(components); specifications = Map.copyOf(specifications);
        pins = List.copyOf(pins); nets = Map.copyOf(nets);
        connections = List.copyOf(connections); issues = List.copyOf(issues);
        sourceMetadata = Map.copyOf(sourceMetadata);
    }
}
