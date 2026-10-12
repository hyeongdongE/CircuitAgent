package com.circuitagent.design.model;

/**
 * 한 설계 내 전기적으로 같은 연결망. id는 모델 식별자, name은 원문 Net 이름이다. sourcePath는 연결 근거이며 이름이 12V여도 실제 측정 전압을 뜻하지
 * 않는다.
 */
public record CircuitNet(String id, String name, String sourcePath) {
  public CircuitNet {
    ModelChecks.required(id, "net id");
    ModelChecks.required(name, "net name");
  }
}
