package com.circuitagent.design.model;

import java.util.Map;

public record ComponentPin(PinKey key, String name, String electricalType,
                           PinDisposition disposition, Map<String, String> sourceMetadata) {
    public ComponentPin(PinKey key, String name, String electricalType) {
        this(key, name, electricalType, PinDisposition.UNSPECIFIED, Map.of());
    }
    public ComponentPin {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(disposition, "disposition");
        sourceMetadata = Map.copyOf(sourceMetadata);
    }
}
