package com.circuitagent.audit.poc;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Audit 요청 오류를 안전한 HTTP 코드/메시지로 구분한다. 잘못된 계약은400, 없는 설계/검사는404, DB 충돌은409, 내부 요청 실패는 상세 없는500이다.
 */
@RestControllerAdvice(basePackageClasses = AuditController.class)
public class AuditApiExceptionHandler {
  public record Error(String code, String message) {}

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Error> missing() {
    return ResponseEntity.status(404).body(new Error("NOT_FOUND", "Design or audit not found"));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    java.io.IOException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Error> invalid() {
    return ResponseEntity.badRequest()
        .body(new Error("RULE_INPUT_INVALID", "Invalid audit request or poc-rule-v1 contract"));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<Error> conflict() {
    return ResponseEntity.status(409)
        .body(new Error("AUDIT_CONFLICT", "Audit persistence constraint violation"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Error> unexpected() {
    return ResponseEntity.status(500)
        .body(new Error("AUDIT_EXECUTION_ERROR", "Audit request failed internally"));
  }
}
