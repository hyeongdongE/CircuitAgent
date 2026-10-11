package com.circuitagent.audit.poc;
import com.circuitagent.design.graph.CircuitGraph;
import com.circuitagent.design.model.*;
import java.util.*;
public record AuditEvidence(AuditRule.DatasheetSource datasheet, String condition,
                            Map<String,ComponentInstance> components, Map<String,PartSpecification> specifications,
                            List<CircuitGraph.PinContext> pins, Map<String,String> calculation, List<ImportIssue> issues) {
    public AuditEvidence {components=Map.copyOf(components);specifications=Map.copyOf(specifications);pins=List.copyOf(pins);calculation=Map.copyOf(calculation);issues=List.copyOf(issues);}
}
