package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.circuitagent.design.model.*;
import java.util.*;
import java.time.Instant;

@Entity
@Table(name = "import_issue")
public class ImportIssueEntity {
    @Id UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "design_id") CircuitDesignEntity design;
    @Column(nullable = false) int issueOrder;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) ImportIssue.Severity severity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) ImportIssue.Code code;
    @Column(name = "component_reference", length = 191) String reference;
    @Column(columnDefinition = "text") String source;
    @Column(nullable = false, columnDefinition = "text") String message;
    protected ImportIssueEntity() {}
}
