package com.circuitagent.design.graph;

import com.circuitagent.design.model.*;
import java.util.*;

/**
 * 불변 설계 위에 조회 인덱스를 만든 무방향 연결 모델. Component → Pin → Net → Pin → Component로 탐색하되 부품 양쪽 Net은 합치지 않는다.
 * 핀·연결의 사실을 제공하며 전압·도통·FB/EN 회로 의미는 별도 Rule Checker의 책임이다.
 */
public final class CircuitGraph {
  private final CircuitDesign design;
  // 네 가지 인덱스: 핀 자체, 부품별 핀, 핀의 Net, Net별 핀. 같은 Net을 여러 번 스캔하지 않기 위한 구조다.
  private final Map<PinKey, ComponentPin> pins = new HashMap<>();
  private final Map<String, List<ComponentPin>> byComponent = new HashMap<>();
  private final Map<PinKey, String> netOfPin = new HashMap<>();
  private final Map<String, List<ComponentPin>> byNet = new HashMap<>();

  // 생성자에서 인덱스를 한 번 만든다. 원본 CircuitDesign은 이미 관계 검사/방어적 복사를 마쳤다.
  public CircuitGraph(CircuitDesign design) {
    this.design = design;
    for (var pin : design.pins()) {
      pins.put(pin.key(), pin);
      byComponent.computeIfAbsent(pin.key().reference(), key -> new ArrayList<>()).add(pin);
    }
    for (var connection : design.connections()) {
      netOfPin.put(connection.pin(), connection.netId());
      byNet
          .computeIfAbsent(connection.netId(), key -> new ArrayList<>())
          .add(pins.get(connection.pin()));
    }
  }

  public ComponentInstance getComponent(String ref) {
    var component = design.components().get(ref);
    if (component == null) throw new NoSuchElementException("Component not found: " + ref);
    return component;
  }

  // 반환 목록을 복사하므로 외부 코드가 그래프 인덱스를 수정할 수 없다.
  public List<ComponentPin> getComponentPins(String ref) {
    getComponent(ref);
    return List.copyOf(byComponent.getOrDefault(ref, List.of()));
  }

  /** 핀이 존재하지만 연결 Net이 없으면 Optional.empty(). 핀 자체가 없으면 예외이므로 두 상태를 구분한다. */
  public Optional<CircuitNet> getNetOfPin(String ref, String number) {
    var key = new PinKey(ref, number);
    if (!pins.containsKey(key))
      throw new NoSuchElementException("Pin not found: " + ref + "/" + number);
    return Optional.ofNullable(netOfPin.get(key)).map(design.nets()::get);
  }

  /** Net ID에 속하는 전체 핀을 반환한다. 부품 양쪽 핀을 통해 다른 Net으로 자동 확장하지 않는다. */
  public List<ComponentPin> getConnectedPins(String netId) {
    if (!design.nets().containsKey(netId))
      throw new NoSuchElementException("Net not found: " + netId);
    return List.copyOf(byNet.getOrDefault(netId, List.of()));
  }

  /** 같은 부품의 여러 핀이 한 Net에 있어도 부품은 한 번만 반환한다. */
  public Set<ComponentInstance> getComponentsOnNet(String netId) {
    var result = new LinkedHashSet<ComponentInstance>();
    for (var pin : getConnectedPins(netId)) result.add(getComponent(pin.key().reference()));
    return Collections.unmodifiableSet(result);
  }

  public PartSpecification getComponentSpecification(String ref) {
    return design.specifications().get(getComponent(ref).partSpecificationId());
  }

  public Map<String, AttributeValue> getComponentAttributes(String ref) {
    return getComponentSpecification(ref).attributes();
  }

  public UUID designId() {
    return design.id();
  }

  /** DB를 다시 읽지 않고 버전과 해시 근거에 사용할 불변 Snapshot을 반환한다. */
  public CircuitDesign designSnapshot() {
    return design;
  }

  /** 해당 부품 이슈와 설계 전체 이슈를 함께 반환하여 평가자가 누락·충돌을 확인할 수 있게 한다. */
  public List<ImportIssue> getComponentIssues(String ref) {
    getComponent(ref);
    return design.issues().stream()
        .filter(i -> i.reference() == null || ref.equals(i.reference()))
        .toList();
  }

  /** GND처럼 같은 이름의 핀이 여러 개일 수 있으므로 일치하는 모든 핀을 반환한다. */
  public List<ComponentPin> getPinsByName(String ref, String name) {
    Objects.requireNonNull(name, "name");
    return getComponentPins(ref).stream().filter(p -> name.equals(p.name())).toList();
  }

  /** 조회 상태: 확정 연결/미연결/종단/충돌/미확정/핀 미존재. 선언만 있는 PinDisposition보다 상세하다. */
  public enum ConnectionStatus {
    CONNECTED,
    UNCONNECTED,
    TERMINATED,
    AMBIGUOUS,
    UNRESOLVED,
    MISSING_PIN
  }

  /** 핀 조회에 필요한 부품·사양·Net·문제를 묶는다. MISSING_PIN에서는 pin과 net이 null일 수 있다. */
  public record PinContext(
      PinKey key,
      ComponentPin pin,
      ConnectionStatus status,
      CircuitNet net,
      ComponentInstance component,
      PartSpecification specification,
      List<ImportIssue> issues) {}

  // 연결 누락, 명시 미연결, TERM, 복수 Net 충돌을 구분한다. 라이브러리 목록이 없으면 생략 핀을 추정하지 않는다.
  public PinContext getPinContext(String ref, String number) {
    var component = getComponent(ref);
    var key = new PinKey(ref, number);
    var pin = pins.get(key);
    var net = Optional.ofNullable(netOfPin.get(key)).map(design.nets()::get).orElse(null);
    boolean ambiguous =
        design.issues().stream()
            .anyMatch(
                i ->
                    i.code() == ImportIssue.Code.AMBIGUOUS_CONNECTION
                        && ref.equals(i.reference())
                        && (("pin:" + number).equals(i.source())
                            || i.message()
                                .startsWith("Pin " + number + " assigned to multiple Nets")));
    boolean inventoryKnown =
        "complete"
            .equals(component.rawSources().getOrDefault("netlist", Map.of()).get("pinInventory"));
    ConnectionStatus status =
        pin == null
            ? ConnectionStatus.MISSING_PIN
            : ambiguous
                ? ConnectionStatus.AMBIGUOUS
                : net != null
                    ? ConnectionStatus.CONNECTED
                    : pin.disposition() == PinDisposition.TERMINATED
                        ? ConnectionStatus.TERMINATED
                        : pin.disposition() == PinDisposition.UNCONNECTED || inventoryKnown
                            ? ConnectionStatus.UNCONNECTED
                            : ConnectionStatus.UNRESOLVED;
    return new PinContext(
        key, pin, status, net, component, getComponentSpecification(ref), getComponentIssues(ref));
  }

  public enum EdgeKind {
    NET,
    COMPONENT
  }

  public record TraversalEdge(
      PinKey from, PinKey to, EdgeKind kind, String netId, String sourcePath) {
    public TraversalEdge(PinKey from, PinKey to, EdgeKind kind, String netId) {
      this(from, to, kind, netId, null);
    }
  }

  public enum TraversalMode {
    EXCLUDE_DNP,
    INCLUDE_DNP_SCHEMATIC
  }

  public record Neighborhood(Set<PinKey> pins, Set<String> nets, Set<TraversalEdge> edges) {}

  /**
   * 제한된 깊이의 위상 탐색. 부품 경유에는 호출자가 명시한 허용 목록이 필요하다. 경유는 부품 양쪽 핀의 관계이며 Net 병합이나 실제 도통 판정이 아니다. 기본 탐색은
   * DNP를 경유하지 않고 IC/모든 2핀 부품을 자동으로 통과하지 않는다.
   */
  public Neighborhood explore(PinKey start, Set<String> passThroughReferences, int maxSteps) {
    return explore(start, passThroughReferences, maxSteps, TraversalMode.EXCLUDE_DNP);
  }

  // BFS로 제한 깊이만 탐색한다. 부품 경유는 caller allow-list로만 허용하며 실제 도통을 의미하지 않는다.
  public Neighborhood explore(
      PinKey start, Set<String> passThroughReferences, int maxSteps, TraversalMode mode) {
    Objects.requireNonNull(mode, "mode");
    if (maxSteps < 0 || maxSteps > 32)
      throw new IllegalArgumentException("maxSteps must be between 0 and 32");
    getNetOfPin(start.reference(), start.number()); // 시작 핀 존재 여부를 확인한다. 미연결 핀도 유효한 시작점이다.
    passThroughReferences.forEach(this::getComponent);
    var reached = new LinkedHashSet<PinKey>();
    var reachedNets = new LinkedHashSet<String>();
    var edges = new LinkedHashSet<TraversalEdge>();
    // 각 핀의 최초 방문 깊이를 큐에 보관해 순환 회로에서도 무한 반복하지 않는다.
    record Visit(PinKey pin, int depth) {}
    var queue = new ArrayDeque<Visit>();
    reached.add(start);
    queue.add(new Visit(start, 0));
    while (!queue.isEmpty()) {
      var visit = queue.remove();
      String netId = netOfPin.get(visit.pin());
      if (netId != null) reachedNets.add(netId);
      if (visit.depth() == maxSteps) continue;
      var next = new ArrayList<TraversalEdge>();
      // NET edge는 실제 같은 연결망의 핀 사이만 만든다.
      if (netId != null)
        for (var pin : getConnectedPins(netId))
          if (!pin.key().equals(visit.pin()))
            next.add(edge(visit.pin(), pin.key(), EdgeKind.NET, netId));
      String ref = visit.pin().reference();
      // COMPONENT edge는 핀 사이의 부품 경유를 표현한다. 기본 모드는 DNP 경유를 제외한다.
      if (passThroughReferences.contains(ref)
          && (mode == TraversalMode.INCLUDE_DNP_SCHEMATIC
              || getComponent(ref).assemblyStatus() != AssemblyStatus.DNP))
        for (var pin : getComponentPins(ref))
          if (!pin.key().equals(visit.pin()))
            next.add(edge(visit.pin(), pin.key(), EdgeKind.COMPONENT, null));
      for (var e : next) {
        edges.add(e);
        var destination = e.from().equals(visit.pin()) ? e.to() : e.from();
        if (reached.add(destination)) queue.add(new Visit(destination, visit.depth() + 1));
        if (reached.size() > 10_000 || edges.size() > 50_000)
          throw new IllegalArgumentException(
              "Traversal budget exceeded; reduce maxSteps/allow-list");
      }
    }
    return new Neighborhood(Set.copyOf(reached), Set.copyOf(reachedNets), Set.copyOf(edges));
  }

  // 핀 순서를 정렬하여 A-B와B-A가 같은 무방향 edge로 중복 제거되게 한다.
  private TraversalEdge edge(PinKey a, PinKey b, EdgeKind kind, String netId) {
    int order = Comparator.comparing(PinKey::reference).thenComparing(PinKey::number).compare(a, b);
    String source =
        kind == EdgeKind.NET
            ? design.nets().get(netId).sourcePath()
            : getComponent(a.reference())
                .rawSources()
                .getOrDefault("provenance", Map.of())
                .getOrDefault("bomLocation", getComponent(a.reference()).sourcePath());
    return order <= 0
        ? new TraversalEdge(a, b, kind, netId, source)
        : new TraversalEdge(b, a, kind, netId, source);
  }
}
