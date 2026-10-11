package com.circuitagent.audit.poc;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;
@Entity @Table(name="audit_result")
class AuditResultEntity {
    @Id UUID id;@Column(nullable=false) UUID executionId;@Column(nullable=false) UUID designId;
    @Column(nullable=false) int resultOrder;@Column(nullable=false,length=128) String ruleId;
    @Column(nullable=false,length=64) String ruleVersion;@Column(nullable=false,length=191) String componentReference;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) AuditStatus status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="json") java.util.Map<String,String> payload;
    protected AuditResultEntity(){}
}
