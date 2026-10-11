package com.circuitagent.audit.poc;
import java.math.BigDecimal;

/** Explicit PoC contract, not an implementation or assumption of Stage 01 cp-v1. */
public record AuditRule(String id, String version, Type type, Target target, String condition,
                        boolean applicable, String notApplicableReason, String attribute, String unit,
                        Bounds expected, String expectedNet, Divider divider, DatasheetSource source) {
    public enum Type { VALUE_RANGE, ATTRIBUTE_PRESENT, PIN_CONNECTED, NET_MATCH, FB_DIVIDER }
    public record Target(String reference, String partName, String pinNumber, String pinFunction, boolean requireVerifiedMpn) {}
    public record Bounds(BigDecimal min, BigDecimal max, BigDecimal tolerancePercent) {}
    public record Divider(String upperReference, String lowerReference, String groundPin,
                          BigDecimal vref, BigDecimal vrefMin, BigDecimal vrefMax, BigDecimal resistorTolerancePercent) {}
    public record DatasheetSource(String url, String revision, int page, String section, String summary, String sha256) {}
}
