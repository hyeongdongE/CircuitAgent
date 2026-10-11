package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.circuitagent.design.model.*;
import java.util.*;
import java.time.Instant;

@Entity
@Table(name = "component_pin", uniqueConstraints = @UniqueConstraint(columnNames = {"component_id", "number"}))
public class ComponentPinEntity {
    @Id UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "component_id") ComponentInstanceEntity component;
    @Column(name = "design_id", nullable = false) UUID designId;
    @Column(nullable = false, length = 128) String number;
    @Column(columnDefinition = "text") String name;
    @Column(length = 128) String electricalType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) PinDisposition disposition;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "json") Map<String, String> sourceMetadata;
    protected ComponentPinEntity() {}
}
