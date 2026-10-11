package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.circuitagent.design.model.*;
import java.util.*;
import java.time.Instant;

@Entity
@Table(name = "circuit_design", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}))
public class CircuitDesignEntity {
    @Id UUID id;
    @Column(nullable = false, length = 191) String name;
    @Column(nullable = false, length = 64) String version;
    @Column(nullable = false) Instant importedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "json") Map<String, String> sourceMetadata;
    @ManyToMany
    @JoinTable(name = "circuit_design_specification", joinColumns = @JoinColumn(name = "design_id"), inverseJoinColumns = @JoinColumn(name = "specification_id"))
    Set<PartSpecificationEntity> specifications = new LinkedHashSet<>();
    protected CircuitDesignEntity() {}
}
