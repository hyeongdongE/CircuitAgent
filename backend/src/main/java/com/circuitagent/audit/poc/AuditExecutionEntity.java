package com.circuitagent.audit.poc;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="audit_execution")
class AuditExecutionEntity {
    @Id UUID id;@Column(nullable=false) UUID designId;@Column(nullable=false,length=64) String designVersion;
    @Column(nullable=false,length=64) String snapshotHash;@Column(nullable=false) Instant executedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="json") java.util.Map<String,String> metadata;
    protected AuditExecutionEntity(){}
}
