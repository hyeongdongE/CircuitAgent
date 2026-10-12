package com.circuitagent.design.model;

import java.util.Map;
import java.util.UUID;

public record PartSpecification(UUID id, String manufacturer, String mpn, String type,
                                Map<String, AttributeValue> attributes) {
    public PartSpecification {
        java.util.Objects.requireNonNull(id, "id");
        attributes = Map.copyOf(attributes);
    }
}
