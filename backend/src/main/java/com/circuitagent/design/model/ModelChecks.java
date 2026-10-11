package com.circuitagent.design.model;

final class ModelChecks {
    private ModelChecks() {}
    static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
