package com.circuitagent.design.parse;

import com.circuitagent.design.model.*;
import java.util.*;

/**
 * Netlist 어댑터의 공통 출력. 부품 사양이 비어 있어도 실제 핀·Net과 출처를 보존한다. BOM 결합 전의 중간 결과이며 sourceMetadata에는 파일 형식과
 * 관측된 선언 수 등을 담는다.
 */
public record NetlistResult(
    Map<String, ComponentInstance> components,
    Map<UUID, PartSpecification> specifications,
    List<ComponentPin> pins,
    Map<String, CircuitNet> nets,
    List<PinConnection> connections,
    List<ImportIssue> issues,
    Map<String, String> sourceMetadata) {
  public NetlistResult {
    components = Map.copyOf(components);
    specifications = Map.copyOf(specifications);
    pins = List.copyOf(pins);
    nets = Map.copyOf(nets);
    connections = List.copyOf(connections);
    issues = List.copyOf(issues);
    sourceMetadata = Map.copyOf(sourceMetadata);
  }
}
