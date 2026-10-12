package com.circuitagent.design.model;

import java.util.Map;

/**
 * 개별 부품의 핀. key의 번호가 식별자이며 name/electricalType은 원본에 없으면 null이다. disposition은 원본 선언 상태,
 * sourceMetadata는 파일·행·선언 근거이며 실제 Net 연결은 PinConnection이다.
 */
public record ComponentPin(
    PinKey key,
    String name,
    String electricalType,
    PinDisposition disposition,
    Map<String, String> sourceMetadata) {
  public ComponentPin(PinKey key, String name, String electricalType) {
    this(key, name, electricalType, PinDisposition.UNSPECIFIED, Map.of());
  }

  public ComponentPin {
    java.util.Objects.requireNonNull(key, "key");
    java.util.Objects.requireNonNull(disposition, "disposition");
    sourceMetadata = Map.copyOf(sourceMetadata);
  }
}
