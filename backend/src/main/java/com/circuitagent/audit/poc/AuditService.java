package com.circuitagent.audit.poc;

import com.circuitagent.design.graph.DesignQueryService;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 선택된 catalog/custom Rule → Snapshot 한 번 복원 → 평가 → 결과 저장 흐름의 서비스. Stage 02를 재파싱하지 않고 사용하며 HTTP 응답은
 * 평가/저장 완료 후 반환한다.
 */
@Service
public class AuditService {
  private final DesignQueryService queries;
  private final AuditRunner runner;
  private final AuditStore store;
  private final PocRuleCatalog catalog;
  private final StructuredRuleAdapter adapter;

  public AuditService(
      DesignQueryService q,
      AuditRunner r,
      AuditStore s,
      PocRuleCatalog c,
      StructuredRuleAdapter a) {
    queries = q;
    runner = r;
    store = s;
    catalog = c;
    adapter = a;
  }

  public record Request(String schemaVersion, List<String> ruleIds, List<AuditRule> rules) {}

  public AuditExecution execute(UUID designId, Request request) {
    if (request == null
        || !"poc-rule-v1".equals(request.schemaVersion())
        || (request.ruleIds() != null) == (request.rules() != null))
      throw new IllegalArgumentException("Provide poc-rule-v1 and exactly one of ruleIds or rules");
    // catalog ID 선택 또는 명시 Rule 배열 중 정확히 하나만 받는다. 원본 BOM/QCV 재파싱은 하지 않는다.
    var rules =
        request.ruleIds() != null
            ? catalog.select(request.ruleIds())
            : adapter.validate(request.rules());
    // 설계 DB 복원은 실행당 한 번. 모든 Rule이 동일 불변 graph를 사용한다.
    long started = System.nanoTime();
    var graph = queries.snapshot(designId);
    double loadMs = (System.nanoTime() - started) / 1_000_000.0;
    var result = runner.run(graph, rules);
    var timing = new HashMap<>(result.timingMs());
    timing.put("snapshotLoadMs", loadMs);
    var execution =
        new AuditExecution(
            result.id(),
            result.designId(),
            result.designVersion(),
            result.snapshotHash(),
            result.ruleSnapshot(),
            result.results(),
            result.executedAt(),
            timing);
    // 평가가 FAIL/UNKNOWN이어도 정상 결과를 저장한다. persistence 오류만 요청 실패로 처리한다.
    store.save(execution);
    return execution;
  }
}
