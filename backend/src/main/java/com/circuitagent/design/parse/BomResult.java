package com.circuitagent.design.parse;

import com.circuitagent.design.model.*;
import java.util.*;

/**
 * BOM 파싱 결과. components는 Reference로, specifications는 UUID로 조회한다. 아직 Net 연결은 없으며 CircuitAssembler가
 * NetlistResult와 같은 Reference만 결합한다.
 */
public record BomResult(
    Map<String, ComponentInstance> components,
    Map<UUID, PartSpecification> specifications,
    List<ImportIssue> issues) {
  public BomResult {
    components = Map.copyOf(components);
    specifications = Map.copyOf(specifications);
    issues = List.copyOf(issues);
  }
}
