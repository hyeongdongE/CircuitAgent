package com.circuitagent.design.model;

/**
 * Reference와 Pin Number의 복합 식별자. 핀 1은 부품별로 별개다. 숫자처럼 보이는 핀도 문자열로 유지해 A1 등의 물리 핀 번호를 지원하며 핀 이름으로
 * 대체하지 않는다.
 */
public record PinKey(String reference, String number) {
  public PinKey {
    ModelChecks.required(reference, "reference");
    ModelChecks.required(number, "pin number");
  }
}
