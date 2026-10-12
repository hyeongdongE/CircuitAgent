package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 설계 헤더의 JPA 표현. name/version이 설계 버전을 구분한다. 사양 링크를 별도로 유지하여 부품이 참조하지 않는 사양도 복원 시 유실되지 않게 한다. */
@Entity
@Table(
    name = "circuit_design",
    uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}))
public class CircuitDesignEntity {
  @Id UUID id;

  @Column(nullable = false, length = 191)
  String name;

  @Column(nullable = false, length = 64)
  String version;

  @Column(nullable = false)
  Instant importedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "json")
  Map<String, String> sourceMetadata;

  @ManyToMany
  @JoinTable(
      name = "circuit_design_specification",
      joinColumns = @JoinColumn(name = "design_id"),
      inverseJoinColumns = @JoinColumn(name = "specification_id"))
  Set<PartSpecificationEntity> specifications = new LinkedHashSet<>();

  protected CircuitDesignEntity() {}
}
