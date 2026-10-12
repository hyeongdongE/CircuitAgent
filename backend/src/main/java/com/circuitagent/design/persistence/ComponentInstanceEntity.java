package com.circuitagent.design.persistence;

import com.circuitagent.design.model.AssemblyStatus;
import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 설계별 Reference의 DB 표현. shared specification FK와 배치별 assemblyStatus/rawSources를 분리한다.
 * unique(design_id, component_reference)는 한 설계의 중복 Reference를 막는다.
 */
@Entity
@Table(
    name = "component_instance",
    uniqueConstraints = @UniqueConstraint(columnNames = {"design_id", "component_reference"}))
public class ComponentInstanceEntity {
  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "design_id")
  CircuitDesignEntity design;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "specification_id")
  PartSpecificationEntity specification;

  @Column(name = "component_reference", nullable = false, length = 191)
  String reference;

  @Column(name = "component_value", columnDefinition = "text")
  String value;

  @Column(columnDefinition = "text")
  String sourcePath;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "json")
  Map<String, Map<String, String>> rawSources;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  AssemblyStatus assemblyStatus;

  protected ComponentInstanceEntity() {}
}
