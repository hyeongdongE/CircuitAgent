package com.circuitagent.audit.poc;

/**
 * Rule 한 건의 판정. PASS/FAIL은 확인 가능한 조건, UNKNOWN은 정보 부족/모호함이다. NOT_APPLICABLE은 명시적 적용 제외, ERROR는 엔진 실행
 * 실패이며 HTTP 요청 상태와는 다르다.
 */
public enum AuditStatus {
  PASS,
  FAIL,
  UNKNOWN,
  NOT_APPLICABLE,
  ERROR
}
