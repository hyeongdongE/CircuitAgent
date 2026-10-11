package com.circuitagent.audit.poc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
public record AuditResult(UUID id,String ruleId,String ruleVersion,String reference,AuditStatus status,
                          @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal measured,AuditRule.Bounds expected,String unit,String reason,
                          List<String> missingInputs,AuditEvidence evidence,Instant executedAt) {
    public AuditResult {
        Objects.requireNonNull(id);Objects.requireNonNull(status);Objects.requireNonNull(evidence);Objects.requireNonNull(executedAt);
        if(reason==null || reason.isBlank())throw new IllegalArgumentException("Audit reason required");
        missingInputs=List.copyOf(missingInputs);
        if(status==AuditStatus.UNKNOWN && missingInputs.isEmpty())throw new IllegalArgumentException("UNKNOWN requires explicit missing or ambiguous inputs");
    }
}
