package com.circuitagent.design.model;

public record ImportIssue(Severity severity, Code code, String reference, String source, String message) {
    public enum Severity { WARNING, ERROR }
    public enum Code {
        DUPLICATE_REFERENCE, MISSING_REFERENCE, MISSING_VALUE, QUANTITY_MISMATCH,
        INVALID_UNIT, PARSE_FAILURE, BOM_ONLY_COMPONENT, NETLIST_ONLY_COMPONENT,
        VALUE_MISMATCH, SPECIFICATION_CONFLICT, AMBIGUOUS_CONNECTION, UNKNOWN_COMPONENT, DUPLICATE_NET
    }
    public ImportIssue {
        java.util.Objects.requireNonNull(severity, "severity");
        java.util.Objects.requireNonNull(code, "code");
        ModelChecks.required(message, "message");
    }
}
