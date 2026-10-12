package com.circuitagent.design.model;

import java.math.BigDecimal;

/**
 * 부품 속성의 원문(raw), SI 숫자(normalized), 단위(unit)를 함께 보존한다. normalized=null은 숫자 해석/입력이 없다는 뜻이며 실제 값 0과
 * 구분한다. text는 원문만 있는 속성이다.
 */
public record AttributeValue(String raw, BigDecimal normalized, String unit) {
  public AttributeValue {
    if (normalized != null) {
      ModelChecks.required(unit, "unit");
      normalized = normalized.stripTrailingZeros();
    }
  }

  public static AttributeValue text(String raw) {
    return new AttributeValue(raw, null, null);
  }
}
