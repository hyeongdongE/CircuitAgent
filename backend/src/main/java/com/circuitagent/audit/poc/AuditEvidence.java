package com.circuitagent.audit.poc;

import com.circuitagent.design.graph.CircuitGraph;
import com.circuitagent.design.model.*;
import java.util.*;

/**
 * 판정 당시 실제로 사용한 데이터시트·부품/사양·PinContext·이슈·계산 문자열의 불변 묶음. 원본 행과 연결 출처를 포함하므로 민감할 수 있으며 로컬 PoC 경계
 * 안에서만 제공한다.
 */
public record AuditEvidence(
    AuditRule.DatasheetSource datasheet,
    String condition,
    Map<String, ComponentInstance> components,
    Map<String, PartSpecification> specifications,
    List<CircuitGraph.PinContext> pins,
    Map<String, String> calculation,
    List<ImportIssue> issues) {
  public AuditEvidence {
    components = Map.copyOf(components);
    specifications = Map.copyOf(specifications);
    pins = List.copyOf(pins);
    calculation = Map.copyOf(calculation);
    issues = List.copyOf(issues);
  }
}
