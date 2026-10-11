package com.circuitagent.audit.poc;

// AuditResultTest: 대상 기능의 정상 동작과 오류 경계를 검증한다. 입력과 기대값은 각 테스트 메서드에 명시한다.

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuditResultTest {
  @Test
  void preservesFiveDifferentStatesEvidenceMissingInputsAndRealZero() {
    var e =
        new AuditEvidence(
            RuleInputTest.SOURCE,
            "synthetic",
            Map.of(),
            Map.of(),
            List.of(),
            Map.of("formula", "fixture"),
            List.of());
    for (var s : AuditStatus.values()) {
      var r =
          new AuditResult(
              UUID.randomUUID(),
              "rule",
              "v1",
              "R1",
              s,
              BigDecimal.ZERO,
              null,
              "Ω",
              "Explicit reason",
              s == AuditStatus.UNKNOWN ? List.of("MPN") : List.of(),
              e,
              Instant.now());
      assertThat(r.status()).isEqualTo(s);
      assertThat(r.measured()).isEqualByComparingTo("0");
      assertThat(r.evidence().datasheet()).isEqualTo(RuleInputTest.SOURCE);
      assertThatThrownBy(() -> r.missingInputs().add("x"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
    assertThatThrownBy(
            () ->
                new AuditResult(
                    UUID.randomUUID(),
                    "r",
                    "v",
                    "R1",
                    AuditStatus.UNKNOWN,
                    null,
                    null,
                    null,
                    "reason",
                    List.of(),
                    e,
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
