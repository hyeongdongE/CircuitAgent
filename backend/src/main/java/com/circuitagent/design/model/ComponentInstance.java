package com.circuitagent.design.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 회로에 배치된 개별 부품(R1, U16 등). 같은 부품 사양도 배치는 서로 다르다. partSpecificationId는 공유 사양을 가리키고, assemblyStatus는
 * 이 배치의 실장 상태다. rawSources에는 BOM/Netlist 원문과 provenance를 함께 보존하며 value 누락은 null이다.
 */
public record ComponentInstance(
    String reference,
    String value,
    UUID partSpecificationId,
    Map<String, Map<String, String>> rawSources,
    String sourcePath,
    AssemblyStatus assemblyStatus) {
  public ComponentInstance(
      String reference,
      String value,
      UUID specification,
      Map<String, Map<String, String>> rawSources,
      String sourcePath) {
    this(reference, value, specification, rawSources, sourcePath, AssemblyStatus.UNSPECIFIED);
  }

  public ComponentInstance {
    ModelChecks.required(reference, "reference");
    java.util.Objects.requireNonNull(assemblyStatus, "assemblyStatus");
    java.util.Objects.requireNonNull(partSpecificationId, "partSpecificationId");
    var copy = new LinkedHashMap<String, Map<String, String>>();
    rawSources.forEach((key, row) -> copy.put(key, Map.copyOf(row)));
    rawSources = Map.copyOf(copy);
  }
}
