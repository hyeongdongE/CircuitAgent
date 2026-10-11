package com.circuitagent.design.model;

/** ID is scoped to a design; hierarchical names are preserved verbatim. */
public record CircuitNet(String id, String name, String sourcePath) {
    public CircuitNet {
        ModelChecks.required(id, "net id");
        ModelChecks.required(name, "net name");
    }
}
