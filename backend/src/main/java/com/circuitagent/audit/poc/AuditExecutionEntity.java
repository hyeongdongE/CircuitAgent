package com.circuitagent.audit.poc;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 검증 실행의 설계 FK/버전/해시/시간과 Rule Snapshot metadata 저장 행. metadata의 json 문자열은 소수·시간을 정확히 복원하는
 * envelope이며 이력 필드는 별도 인덱스 컬럼이다.
 */
@Entity
@Table(name = "audit_execution")
class AuditExecutionEntity {
  @Id UUID id;

  @Column(nullable = false)
  UUID designId;

  @Column(nullable = false, length = 64)
  String designVersion;

  @Column(nullable = false, length = 64)
  String snapshotHash;

  @Column(nullable = false)
  Instant executedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "json")
  java.util.Map<String, String> metadata;

  protected AuditExecutionEntity() {}
}
