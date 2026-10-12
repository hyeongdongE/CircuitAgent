package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.circuitagent.design.model.*;
import java.util.*;
import java.time.Instant;

@Entity
@Table(name = "circuit_net", uniqueConstraints = @UniqueConstraint(columnNames = {"design_id", "net_code"}))
public class CircuitNetEntity {
    @Id UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "design_id") CircuitDesignEntity design;
    @Column(nullable = false, length = 191) String netCode;
    @Column(nullable = false, columnDefinition = "text") String name;
    @Column(columnDefinition = "text") String sourcePath;
    protected CircuitNetEntity() {}
}
