package com.circuitagent.design.parse;

import com.circuitagent.design.model.*;
import java.util.*;

public record BomResult(Map<String, ComponentInstance> components,
                        Map<UUID, PartSpecification> specifications, List<ImportIssue> issues) {
    public BomResult {
        components = Map.copyOf(components);
        specifications = Map.copyOf(specifications);
        issues = List.copyOf(issues);
    }
}
