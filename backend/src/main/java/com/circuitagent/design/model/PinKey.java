package com.circuitagent.design.model;

/** A pin number is scoped to a component. Names never substitute for numbers. */
public record PinKey(String reference, String number) {
    public PinKey {
        ModelChecks.required(reference, "reference");
        ModelChecks.required(number, "pin number");
    }
}
