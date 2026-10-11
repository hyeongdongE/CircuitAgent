package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import java.util.*;

/** 역할별 원본 bytes, 파일명, SHA256 저장 행. 설계+role은 unique다. 원본은 재현/검토용이며 화면에 파일 전체를 자동 반환하지 않는다. */
@Entity
@Table(
    name = "import_file",
    uniqueConstraints = @UniqueConstraint(columnNames = {"design_id", "role"}))
public class ImportFileEntity {
  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "design_id")
  CircuitDesignEntity design;

  @Column(nullable = false, length = 16)
  String role;

  @Column(nullable = false, length = 512)
  String filename;

  @Column(nullable = false, length = 64)
  String sha256;

  @Lob
  @Column(nullable = false, columnDefinition = "longblob")
  byte[] content;

  protected ImportFileEntity() {}
}
