package com.circuitagent.design.graph;

import static com.circuitagent.design.model.ImportIssue.*;

import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * BOM의 부품 사양과 Netlist의 연결을 Reference 기준으로 결합한다. BOM에만/Netlist에만 있는 부품도 유지하며 양쪽 값의 충돌은 이슈로 남긴다. 새 설계
 * UUID를 만들지만 핀·Net의 전기 연결은 원본 Netlist 그대로 사용한다.
 */
@Component
public class CircuitAssembler {
  public CircuitDesign assemble(String name, String version, BomResult bom, NetlistResult netlist) {
    // BOM의 사양/실장 상태를 기준으로 시작하고 두 입력의 이슈를 모두 유지한다.
    var components = new LinkedHashMap<>(bom.components());
    var specs = new LinkedHashMap<>(bom.specifications());
    var issues = new ArrayList<>(bom.issues());
    issues.addAll(netlist.issues());
    for (var entry : netlist.components().entrySet()) {
      var netComponent = entry.getValue();
      var bomComponent = components.get(entry.getKey());
      // Netlist-only 부품도 삭제하지 않는다. 불완전한 BOM이 전체 topology를 잘라내면 안 된다.
      if (bomComponent == null) {
        components.put(entry.getKey(), netComponent);
        var spec = netlist.specifications().get(netComponent.partSpecificationId());
        specs.put(spec.id(), spec);
        issues.add(
            new ImportIssue(
                Severity.WARNING,
                Code.NETLIST_ONLY_COMPONENT,
                entry.getKey(),
                "netlist",
                "Reference missing from BOM"));
      } else {
        // 사양은 BOM, 연결/계층은 Netlist에서 얻되 두 출처를 한 부품에서 함께 추적한다.
        var raw = new LinkedHashMap<>(bomComponent.rawSources());
        raw.putAll(netComponent.rawSources());
        if (hasValue(bomComponent.value())
            && hasValue(netComponent.value())
            && !equivalent(
                bomComponent.value(),
                netComponent.value(),
                specs.get(bomComponent.partSpecificationId()).type())) {
          issues.add(
              new ImportIssue(
                  Severity.WARNING,
                  Code.VALUE_MISMATCH,
                  entry.getKey(),
                  "bom/netlist",
                  "BOM value '"
                      + bomComponent.value()
                      + "' differs from Netlist value '"
                      + netComponent.value()
                      + "'; BOM value retained"));
        }
        components.put(
            entry.getKey(),
            new ComponentInstance(
                entry.getKey(),
                hasValue(bomComponent.value())
                    ? bomComponent.value()
                    : hasValue(netComponent.value()) ? netComponent.value() : null,
                bomComponent.partSpecificationId(),
                raw,
                netComponent.sourcePath(),
                bomComponent.assemblyStatus()));
      }
    }
    // BOM-only 부품도 보존하지만 없는 핀이나 연결은 생성하지 않는다.
    for (String ref : bom.components().keySet()) {
      if (!netlist.components().containsKey(ref))
        issues.add(
            new ImportIssue(
                Severity.WARNING,
                Code.BOM_ONLY_COMPONENT,
                ref,
                "bom",
                "Reference missing from Netlist; no connectivity inferred"));
    }
    return new CircuitDesign(
        UUID.randomUUID(),
        name,
        version,
        components,
        specs,
        netlist.pins(),
        netlist.nets(),
        netlist.connections(),
        issues,
        netlist.sourceMetadata());
  }

  private static boolean hasValue(String value) {
    return value != null && !value.isBlank() && !value.equals("~");
  }

  // 원문이 달라도1kΩ와1000Ω처럼 같은 정규값이면 값 충돌이 아니다.
  private static boolean equivalent(String bom, String net, String type) {
    if (bom.equals(net)) return true;
    String unit =
        switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
          case "resistor" -> "Ω";
          case "capacitor" -> "F";
          case "inductor" -> "H";
          default -> null;
        };
    if (unit == null) return false;
    try {
      return EngineeringUnits.normalize(bom, unit)
              .normalized()
              .compareTo(EngineeringUnits.normalize(net, unit).normalized())
          == 0;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
