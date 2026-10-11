package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.circuitagent.design.model.*;
import java.util.*;
import java.time.Instant;

@Entity
@Table(name = "import_file", uniqueConstraints = @UniqueConstraint(columnNames = {"design_id", "role"}))
public class ImportFileEntity {
    @Id UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "design_id") CircuitDesignEntity design;
    @Column(nullable = false, length = 16) String role;
    @Column(nullable = false, length = 512) String filename;
    @Column(nullable = false, length = 64) String sha256;
    @Lob @Column(nullable = false, columnDefinition = "longblob") byte[] content;
    protected ImportFileEntity() {}
}
