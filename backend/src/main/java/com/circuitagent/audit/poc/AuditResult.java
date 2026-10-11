package com.circuitagent.audit.poc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Rule 한 건의 판정과 재현 근거. measured는 조회값 또는 조건부 계산값이며 실제 측정값으로 한정되지 않는다. BigDecimal을 JSON 문자열로 반환해 브라우저
 * Number의 정밀도 손실을 피한다. UNKNOWN에는 missingInputs가 필수이며 null을 0으로 대체하지 않는다.
 */
public record AuditResult(
    UUID id,
    String ruleId,
    String ruleVersion,
    String reference,
    AuditStatus status,
    @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal measured,
    AuditRule.Bounds expected,
    String unit,
    String reason,
    List<String> missingInputs,
    AuditEvidence evidence,
    Instant executedAt) {
  public AuditResult {
    Objects.requireNonNull(id);
    Objects.requireNonNull(status);
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(executedAt);
    if (reason == null || reason.isBlank())
      throw new IllegalArgumentException("Audit reason required");
    missingInputs = List.copyOf(missingInputs);
    // 정보가 없다는 이유 없이 UNKNOWN을 저장하지 못하게 해 후속 검토가 가능하도록 한다.
    if (status == AuditStatus.UNKNOWN && missingInputs.isEmpty())
      throw new IllegalArgumentException("UNKNOWN requires explicit missing or ambiguous inputs");
  }
}
