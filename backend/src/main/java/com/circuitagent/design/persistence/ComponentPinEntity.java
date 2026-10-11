package com.circuitagent.design.persistence;

import com.circuitagent.design.model.PinDisposition;
import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Component의 핀 번호/이름/선언 상태/원문 metadata 저장 행. DB PK UUID와 도메인 PinKey는 다르며 designId는 교차 설계 연결 FK를
 * 검사하는 데 사용한다.
 */
@Entity
@Table(
    name = "component_pin",
    uniqueConstraints = @UniqueConstraint(columnNames = {"component_id", "number"}))
public class ComponentPinEntity {
  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "component_id")
  ComponentInstanceEntity component;

  @Column(name = "design_id", nullable = false)
  UUID designId;

  @Column(nullable = false, length = 128)
  String number;

  @Column(columnDefinition = "text")
  String name;

  @Column(length = 128)
  String electricalType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  PinDisposition disposition;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "json")
  Map<String, String> sourceMetadata;

  protected ComponentPinEntity() {}
}
