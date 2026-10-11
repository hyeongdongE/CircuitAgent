package com.circuitagent.audit.poc;

import com.circuitagent.design.graph.CircuitGraph;
import com.fasterxml.jackson.databind.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 이미 복원된 하나의 CircuitGraph로 Rule 검증·탐색·평가를 실행하는 DB 독립 조율자. 결과와 설계/Rule Snapshot 해시를 묶는다. 데이터시트 수치나
 * 설계값을 생성하지 않는다.
 */
@Component
public class AuditRunner {
  private final StructuredRuleAdapter adapter;
  private final DeterministicRuleChecker checker;
  private final ObjectMapper mapper;

  public AuditRunner(StructuredRuleAdapter a, DeterministicRuleChecker c, ObjectMapper m) {
    adapter = a;
    checker = c;
    mapper = m;
  }

  public AuditExecution run(CircuitGraph graph, List<AuditRule> input) {
    // 하나의 Snapshot에서 계측한다. 시간 정보는 참고값이며 PASS/FAIL 임계값이 아니다.
    var times = new LinkedHashMap<String, Double>();
    long start = System.nanoTime();
    var rules = adapter.validate(input);
    times.put("ruleValidationMs", elapsed(start));
    // 이 루프는 별도 traversal preflight 계측이다. 실제 판정은 뒤의 Checker에서도 필요한 연결을 조회한다.
    start = System.nanoTime();
    for (var r : rules) {
      try {
        graph.getComponentSpecification(r.target().reference());
        if (r.target().pinNumber() != null) {
          var p = graph.getPinContext(r.target().reference(), r.target().pinNumber());
          if (p.net() != null) graph.getConnectedPins(p.net().id());
        }
      } catch (NoSuchElementException ignored) {
        /* checker returns UNKNOWN with evidence */
      }
    }
    times.put("circuitTraversalPreflightMs", elapsed(start));
    start = System.nanoTime();
    var results = rules.stream().map(r -> checker.evaluate(graph, r)).toList();
    times.put("ruleEvaluationIncludingGraphLookupsMs", elapsed(start));
    var d = graph.designSnapshot();
    start = System.nanoTime();
    var snapshotHash = hash(graph);
    times.put("snapshotHashMs", elapsed(start));
    return new AuditExecution(
        UUID.randomUUID(), d.id(), d.version(), snapshotHash, rules, results, Instant.now(), times);
  }

  // Map key와 핀/연결 목록을 안정 순서로 정렬해 DB 반환 순서가 바뀌어도 같은 모델 해시를 얻는다.
  public String hash(CircuitGraph graph) {
    try {
      // Map뿐 아니라 연결 목록도 정렬하여 DB 조회 순서가 Snapshot 해시를 바꾸지 않게 한다.
      var d = graph.designSnapshot();
      var tree = mapper.valueToTree(d);
      var key =
          Comparator.comparing((com.circuitagent.design.model.PinKey p) -> p.reference())
              .thenComparing(p -> p.number());
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
          .set(
              "pins",
              mapper.valueToTree(
                  d.pins().stream()
                      .sorted(
                          Comparator.comparing(
                              com.circuitagent.design.model.ComponentPin::key, key))
                      .toList()));
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
          .set(
              "connections",
              mapper.valueToTree(
                  d.connections().stream()
                      .sorted(
                          Comparator.comparing(
                              com.circuitagent.design.model.PinConnection::pin, key))
                      .toList()));
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(sort(tree))));
    } catch (java.io.IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Snapshot hash failed", e);
    }
  }

  private JsonNode sort(JsonNode n) {
    if (n.isObject()) {
      var o = mapper.createObjectNode();
      var names = new ArrayList<String>();
      n.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      names.forEach(k -> o.set(k, sort(n.get(k))));
      return o;
    }
    if (n.isArray()) {
      var a = mapper.createArrayNode();
      n.forEach(v -> a.add(sort(v)));
      return a;
    }
    return n;
  }

  private static double elapsed(long s) {
    return (System.nanoTime() - s) / 1_000_000.0;
  }
}
