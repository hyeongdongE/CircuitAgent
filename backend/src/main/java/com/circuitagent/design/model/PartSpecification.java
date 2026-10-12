package com.circuitagent.design.model;

import java.util.Map;
import java.util.UUID;

/**
 * 제조사/MPN/type 핵심 식별 정보와 동적 attributes를 담는 공유 부품 사양. 저항·용량·정격 등을 고정 컬럼으로 늘리지 않고 AttributeValue
 * Map으로 표현한다. MPN 문자열 존재만으로 제조사나 데이터시트 적용성이 검증된 것은 아니다.
 */
public record PartSpecification(
    UUID id, String manufacturer, String mpn, String type, Map<String, AttributeValue> attributes) {
  public PartSpecification {
    java.util.Objects.requireNonNull(id, "id");
    attributes = Map.copyOf(attributes);
  }
}
