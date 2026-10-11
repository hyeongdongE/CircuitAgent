package com.circuitagent.design.persistence;

import com.circuitagent.design.model.ImportIssue;
import jakarta.persistence.*;
import java.util.*;

/**
 * ImportIssue를 순서와 함께 저장하는 행. enum 문자열로 코드와 severity를 보존한다. issueOrder로 복원해 DB 조회 순서 변화가 모델/근거 순서에
 * 영향을 주지 않게 한다.
 */
@Entity
@Table(name = "import_issue")
public class ImportIssueEntity {
  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "design_id")
  CircuitDesignEntity design;

  @Column(nullable = false)
  int issueOrder;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  ImportIssue.Severity severity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 64)
  ImportIssue.Code code;

  @Column(name = "component_reference", length = 191)
  String reference;

  @Column(columnDefinition = "text")
  String source;

  @Column(nullable = false, columnDefinition = "text")
  String message;

  protected ImportIssueEntity() {}
}
