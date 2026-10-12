package com.circuitagent.design.model;

public record PinConnection(PinKey pin, String netId) {
    public PinConnection {
        java.util.Objects.requireNonNull(pin, "pin");
        ModelChecks.required(netId, "net id");
    }
}
