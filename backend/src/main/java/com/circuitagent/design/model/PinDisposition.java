package com.circuitagent.design.model;

/**
 * 원본이 선언한 핀 상태: UNSPECIFIED=명시 없음, UNCONNECTED=명시적 미연결, TERMINATED=종단. 선언 상태와 조회 시의
 * CONNECTED/AMBIGUOUS 등 연결 판정은 별개이며 TERM을 오류로 만들지 않는다.
 */
public enum PinDisposition {
  UNSPECIFIED,
  UNCONNECTED,
  TERMINATED
}
