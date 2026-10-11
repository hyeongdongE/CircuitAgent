package com.circuitagent.audit.poc;
import java.time.Instant;
import java.util.*;
public record AuditExecution(UUID id, UUID designId,String designVersion,String snapshotHash,
                             List<AuditRule> ruleSnapshot,List<AuditResult> results,Instant executedAt,Map<String,Double> timingMs) {
    public AuditExecution {ruleSnapshot=List.copyOf(ruleSnapshot);results=List.copyOf(results);timingMs=Map.copyOf(timingMs);}
}
