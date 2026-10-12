package com.circuitagent.design.model;

import java.util.*;

/**
 * 한 설계 버전의 DB 독립 불변 모델. Map/List를 복사해 호출자의 외부 변경을 차단한다. 생성 시 부품-사양, 핀-부품, 연결-핀/Net 관계를 검사해 잘못된 그래프가
 * 저장되지 않게 한다.
 */
public record CircuitDesign(
    UUID id,
    String name,
    String version,
    Map<String, ComponentInstance> components,
    Map<UUID, PartSpecification> specifications,
    List<ComponentPin> pins,
    Map<String, CircuitNet> nets,
    List<PinConnection> connections,
    List<ImportIssue> issues,
    Map<String, String> sourceMetadata) {
  public CircuitDesign {
    Objects.requireNonNull(id, "id");
    ModelChecks.required(name, "name");
    ModelChecks.required(version, "version");
    // 호출자의 Map/List 참조를 그대로 저장하지 않아 검증 중 외부 변경이 발생하지 않게 한다.
    components = Map.copyOf(components);
    specifications = Map.copyOf(specifications);
    pins = List.copyOf(pins);
    nets = Map.copyOf(nets);
    connections = List.copyOf(connections);
    issues = List.copyOf(issues);
    sourceMetadata = Map.copyOf(sourceMetadata);
    specifications.forEach(
        (key, spec) -> {
          if (!key.equals(spec.id()))
            throw new IllegalArgumentException("Specification ID mismatch");
        });
    for (var entry : components.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().reference())
          || !specifications.containsKey(entry.getValue().partSpecificationId()))
        throw new IllegalArgumentException("Invalid component specification/reference");
    }
    // PinKey가 설계 내에서 유일하고 실제 부품을 참조해야 한다.
    Set<PinKey> knownPins = new HashSet<>();
    for (var pin : pins) {
      if (!components.containsKey(pin.key().reference()) || !knownPins.add(pin.key()))
        throw new IllegalArgumentException("Unknown component or duplicate pin");
    }
    nets.forEach(
        (key, net) -> {
          if (!key.equals(net.id())) throw new IllegalArgumentException("Net ID mismatch");
        });
    // 존재하는 핀/Net끼리만 연결하고 한 핀에 두 Net을 배정할 수 없게 한다.
    Set<PinKey> connected = new HashSet<>();
    for (var connection : connections) {
      if (!knownPins.contains(connection.pin())
          || !nets.containsKey(connection.netId())
          || !connected.add(connection.pin()))
        throw new IllegalArgumentException("Dangling or ambiguous connection");
    }
  }
}
