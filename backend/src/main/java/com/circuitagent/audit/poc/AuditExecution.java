package com.circuitagent.audit.poc;

import java.time.Instant;
import java.util.*;

/**
 * 검증 실행 한 번의 불변 Snapshot. 설계 버전/해시와 Rule 사본을 결과와 함께 보존한다. Rule이 나중에 바뀌어도 이 실행의 의미는 바뀌지 않으며
 * timingMs는 계측 정보이지 판정 기준이 아니다.
 */
public record AuditExecution(
    UUID id,
    UUID designId,
    String designVersion,
    String snapshotHash,
    List<AuditRule> ruleSnapshot,
    List<AuditResult> results,
    Instant executedAt,
    Map<String, Double> timingMs) {
  public AuditExecution {
    ruleSnapshot = List.copyOf(ruleSnapshot);
    results = List.copyOf(results);
    timingMs = Map.copyOf(timingMs);
  }
}
