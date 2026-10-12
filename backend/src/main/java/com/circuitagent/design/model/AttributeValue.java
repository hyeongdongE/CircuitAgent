package com.circuitagent.design.model;

import java.math.BigDecimal;

/** Raw text is retained; normalized numeric values are absent when unknown. */
public record AttributeValue(String raw, BigDecimal normalized, String unit) {
    public AttributeValue {
        if (normalized != null) {
            ModelChecks.required(unit, "unit");
            normalized = normalized.stripTrailingZeros();
        }
    }
    public static AttributeValue text(String raw) { return new AttributeValue(raw, null, null); }
}
