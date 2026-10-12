package com.circuitagent.audit.poc;

import java.math.BigDecimal;

/**
 * Stage 01 미연동 상태에서 사용하는 명시적 poc-rule-v1 입력 계약. 대상 Reference/핀 번호, 적용 조건, 단위/범위/공차, 데이터시트 출처를 함께
 * 전달한다. Type이 연산을 정하며 임의 자연어나 실행 가능한 수식 코드를 평가하지 않는다.
 */
public record AuditRule(
    String id,
    String version,
    Type type,
    Target target,
    String condition,
    boolean applicable,
    String notApplicableReason,
    String attribute,
    String unit,
    Bounds expected,
    String expectedNet,
    Divider divider,
    DatasheetSource source) {
  public enum Type {
    VALUE_RANGE,
    ATTRIBUTE_PRESENT,
    PIN_CONNECTED,
    NET_MATCH,
    FB_DIVIDER
  }

  // partName은 원본 조건이고 pinFunction은 데이터시트 의미 메모다. 실제 조회 키는 명시 pinNumber다.
  public record Target(
      String reference,
      String partName,
      String pinNumber,
      String pinFunction,
      boolean requireVerifiedMpn) {}

  // 포함 범위와 명시 비교 허용 오차(%). 저항 자체 공차와는 별개의 값이다.
  public record Bounds(BigDecimal min, BigDecimal max, BigDecimal tolerancePercent) {}

  // 상/하 저항은 명시 입력이며 엔진이 이름만으로 추정하지 않는다. vref 범위와 저항 공차로 worst-case를 계산한다.
  public record Divider(
      String upperReference,
      String lowerReference,
      String groundPin,
      BigDecimal vref,
      BigDecimal vrefMin,
      BigDecimal vrefMax,
      BigDecimal resistorTolerancePercent) {}

  // 재현 가능한 공식 근거의 위치와 문서 해시. URL만 있다고 근거 내용이 검증되는 것은 아니다.
  public record DatasheetSource(
      String url, String revision, int page, String section, String summary, String sha256) {}
}
