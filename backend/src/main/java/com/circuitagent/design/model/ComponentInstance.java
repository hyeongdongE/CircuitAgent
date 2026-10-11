package com.circuitagent.design.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ComponentInstance(String reference, String value, UUID partSpecificationId,
                                Map<String, Map<String, String>> rawSources, String sourcePath) {
    public ComponentInstance {
        ModelChecks.required(reference, "reference");
        java.util.Objects.requireNonNull(partSpecificationId, "partSpecificationId");
        var copy = new LinkedHashMap<String, Map<String, String>>();
        rawSources.forEach((key, row) -> copy.put(key, Map.copyOf(row)));
        rawSources = Map.copyOf(copy);
    }
}
