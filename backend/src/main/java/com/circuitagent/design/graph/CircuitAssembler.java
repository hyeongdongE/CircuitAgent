package com.circuitagent.design.graph;

import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.circuitagent.design.model.ImportIssue.*;

@Component
public class CircuitAssembler {
    public CircuitDesign assemble(String name, String version, BomResult bom, NetlistResult netlist) {
        var components = new LinkedHashMap<>(bom.components());
        var specs = new LinkedHashMap<>(bom.specifications());
        var issues = new ArrayList<>(bom.issues());
        issues.addAll(netlist.issues());
        for (var entry : netlist.components().entrySet()) {
            var netComponent = entry.getValue();
            var bomComponent = components.get(entry.getKey());
            if (bomComponent == null) {
                components.put(entry.getKey(), netComponent);
                var spec = netlist.specifications().get(netComponent.partSpecificationId());
                specs.put(spec.id(), spec);
                issues.add(new ImportIssue(Severity.WARNING, Code.NETLIST_ONLY_COMPONENT, entry.getKey(), "netlist", "Reference missing from BOM"));
            } else {
                var raw = new LinkedHashMap<>(bomComponent.rawSources());
                raw.putAll(netComponent.rawSources());
                if (hasValue(bomComponent.value()) && hasValue(netComponent.value())
                    && !equivalent(bomComponent.value(), netComponent.value(), specs.get(bomComponent.partSpecificationId()).type())) {
                    issues.add(new ImportIssue(Severity.WARNING, Code.VALUE_MISMATCH, entry.getKey(), "bom/netlist",
                        "BOM value '" + bomComponent.value() + "' differs from Netlist value '" + netComponent.value() + "'; BOM value retained"));
                }
                components.put(entry.getKey(), new ComponentInstance(entry.getKey(),
                    hasValue(bomComponent.value()) ? bomComponent.value() : hasValue(netComponent.value()) ? netComponent.value() : null,
                    bomComponent.partSpecificationId(), raw, netComponent.sourcePath()));
            }
        }
        for (String ref : bom.components().keySet()) {
            if (!netlist.components().containsKey(ref)) issues.add(new ImportIssue(Severity.WARNING, Code.BOM_ONLY_COMPONENT, ref, "bom", "Reference missing from Netlist; no connectivity inferred"));
        }
        return new CircuitDesign(UUID.randomUUID(), name, version, components, specs, netlist.pins(), netlist.nets(),
            netlist.connections(), issues, netlist.sourceMetadata());
    }
    private static boolean hasValue(String value) { return value != null && !value.isBlank() && !value.equals("~"); }
    private static boolean equivalent(String bom, String net, String type) {
        if (bom.equals(net)) return true;
        String unit = switch (type == null ? "" : type) {
            case "resistor" -> "Ω"; case "capacitor" -> "F"; case "inductor" -> "H"; default -> null;
        };
        if (unit == null) return false;
        try { return EngineeringUnits.normalize(bom, unit).normalized().compareTo(EngineeringUnits.normalize(net, unit).normalized()) == 0; }
        catch (IllegalArgumentException e) { return false; }
    }
}
