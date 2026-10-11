package com.circuitagent.design.model;

public record ComponentPin(PinKey key, String name, String electricalType) {
    public ComponentPin { java.util.Objects.requireNonNull(key, "key"); }
}
