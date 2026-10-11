package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.circuitagent.design.model.*;
import java.util.*;
import java.time.Instant;

@Entity
@Table(name = "component_instance", uniqueConstraints = @UniqueConstraint(columnNames = {"design_id", "component_reference"}))
public class ComponentInstanceEntity {
    @Id UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "design_id") CircuitDesignEntity design;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "specification_id") PartSpecificationEntity specification;
    @Column(name = "component_reference", nullable = false, length = 191) String reference;
    @Column(name = "component_value", columnDefinition = "text") String value;
    @Column(columnDefinition = "text") String sourcePath;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "json") Map<String, Map<String, String>> rawSources;
    protected ComponentInstanceEntity() {}
}
