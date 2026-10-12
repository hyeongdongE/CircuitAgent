package com.circuitagent.design.model;

/**
 * 파싱·통합 과정에서 발견한 문제와 근거. reference=null이면 설계 전체 문제다. severity는 입력의 심각도이며 Audit의 PASS/FAIL과 다르다.
 * source에는 행/핀 등 위치를 기록한다.
 */
public record ImportIssue(
    Severity severity, Code code, String reference, String source, String message) {
  public enum Severity {
    WARNING,
    ERROR
  }

  public enum Code {
    DUPLICATE_REFERENCE,
    MISSING_REFERENCE,
    MISSING_VALUE,
    QUANTITY_MISMATCH,
    INVALID_UNIT,
    PARSE_FAILURE,
    BOM_ONLY_COMPONENT,
    NETLIST_ONLY_COMPONENT,
    VALUE_MISMATCH,
    SPECIFICATION_CONFLICT,
    AMBIGUOUS_CONNECTION,
    UNKNOWN_COMPONENT,
    DUPLICATE_NET,
    DUPLICATE_CONNECTION
  }

  public ImportIssue {
    java.util.Objects.requireNonNull(severity, "severity");
    java.util.Objects.requireNonNull(code, "code");
    ModelChecks.required(message, "message");
  }
}
