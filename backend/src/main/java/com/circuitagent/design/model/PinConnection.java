package com.circuitagent.design.model;

/** 특정 PinKey를 하나의 Net ID에 배정한 전기적 연결 사실. 저항의 반대쪽 핀을 연결하는 것이 아니라 핀과 해당 핀의 Net만 표현한다. */
public record PinConnection(PinKey pin, String netId) {
  public PinConnection {
    java.util.Objects.requireNonNull(pin, "pin");
    ModelChecks.required(netId, "net id");
  }
}
