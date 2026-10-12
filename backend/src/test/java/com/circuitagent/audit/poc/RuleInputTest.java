package com.circuitagent.audit.poc;

// RuleInputTest: 대상 기능의 정상 동작과 오류 경계를 검증한다. 입력과 기대값은 각 테스트 메서드에 명시한다.

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuleInputTest {
  static final AuditRule.DatasheetSource SOURCE =
      new AuditRule.DatasheetSource(
          "https://www.ti.com/lit/ds/symlink/tps54302.pdf",
          "SLVSDG6C",
          15,
          "7.2.3.3 Eq7",
          "Output set point equation",
          "1632b388d1ba3a46c8e8f090ddfec2114c0f538cfb8364ddcda583fee3fdbdc5");

  static AuditRule range(String id, String unit, String min, String max) {
    return new AuditRule(
        id,
        "fixture-v1",
        AuditRule.Type.VALUE_RANGE,
        new AuditRule.Target("R1", null, null, null, false),
        "Own synthetic nominal test; not public circuit data",
        true,
        null,
        "Resistance",
        unit,
        new AuditRule.Bounds(new BigDecimal(min), new BigDecimal(max), BigDecimal.ZERO),
        null,
        null,
        SOURCE);
  }

  @Test
  void validStructuredRuleHasExplicitSourceTargetConditionAndTolerance() {
    assertThat(new StructuredRuleAdapter().validate(List.of(range("a", "Ω", "900", "1100"))))
        .hasSize(1);
  }

  @Test
  void rejectsDuplicateIdsMissingBoundsInvalidUnitsOrUnboundedNumbers() {
    var v = new StructuredRuleAdapter();
    var good = range("a", "Ω", "900", "1100");
    assertThatThrownBy(() -> v.validate(List.of(good, good)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> v.validate(List.of(range("b", "ohms", "1", "2"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> v.validate(List.of(range("c", "Ω", "2", "1"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> v.validate(List.of(range("d", "Ω", "1", "1e1000"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> v.validate(List.of())).isInstanceOf(IllegalArgumentException.class);
  }
}
