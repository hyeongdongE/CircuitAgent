package com.circuitagent.design.persistence;

import com.circuitagent.design.model.AttributeValue;
import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 제조사/MPN/type과 동적 AttributeValue JSON의 DB 표현. 설계의 부품 배치들과 사양을 공유하되 DNP 같은 배치 상태는 저장하지 않는다. */
@Entity
@Table(name = "part_specification")
public class PartSpecificationEntity {
  @Id UUID id;

  @Column(length = 512)
  String manufacturer;

  @Column(length = 512)
  String mpn;

  @Column(name = "component_type", length = 128)
  String type;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "json")
  Map<String, AttributeValue> attributes;

  protected PartSpecificationEntity() {}
}
