package com.circuitagent.audit.poc;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 실행별 Rule 결과의 DB 표현. status/Rule/Reference는 조회용 컬럼, payload는 전체 근거다. 실행+설계 복합 FK와 Rule unique로 다른
 * 설계 연결 및 같은 실행의 중복 결과를 막는다.
 */
@Entity
@Table(name = "audit_result")
class AuditResultEntity {
  @Id UUID id;

  @Column(nullable = false)
  UUID executionId;

  @Column(nullable = false)
  UUID designId;

  @Column(nullable = false)
  int resultOrder;

  @Column(nullable = false, length = 128)
  String ruleId;

  @Column(nullable = false, length = 64)
  String ruleVersion;

  @Column(nullable = false, length = 191)
  String componentReference;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  AuditStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "json")
  java.util.Map<String, String> payload;

  protected AuditResultEntity() {}
}
