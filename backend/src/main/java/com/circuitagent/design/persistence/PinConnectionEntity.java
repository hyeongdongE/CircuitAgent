package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import java.util.*;

/** 핀과 Net의 FK 연결 행. 핀은 한 Net에만 배정되므로 pin FK는 unique다. 설계/핀/Net의 소속 일치는 Flyway의 복합 FK로도 검사한다. */
@Entity
@Table(name = "pin_connection")
public class PinConnectionEntity {
  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "design_id")
  CircuitDesignEntity design;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "pin_id", unique = true)
  ComponentPinEntity pin;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "net_id")
  CircuitNetEntity net;

  protected PinConnectionEntity() {}
}
