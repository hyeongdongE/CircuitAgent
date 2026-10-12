package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import java.util.*;

/**
 * Net의 DB 표현. UUID PK는 저장용이고 netCode는 원본 도메인 Net ID다. netCode는 설계 안에서만 unique이므로 다른 설계의 같은 코드와 혼동하지
 * 않는다.
 */
@Entity
@Table(
    name = "circuit_net",
    uniqueConstraints = @UniqueConstraint(columnNames = {"design_id", "net_code"}))
public class CircuitNetEntity {
  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "design_id")
  CircuitDesignEntity design;

  @Column(nullable = false, length = 191)
  String netCode;

  @Column(nullable = false, columnDefinition = "text")
  String name;

  @Column(columnDefinition = "text")
  String sourcePath;

  protected CircuitNetEntity() {}
}
