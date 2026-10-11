package com.circuitagent.design.model;

/** 도메인 필수 문자열의 공통 검사. 빈 식별자가 이후 Map 키나 DB 관계로 들어가는 것을 막는다. */
final class ModelChecks {
  private ModelChecks() {}

  static String required(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " is required");
    return value;
  }
}
