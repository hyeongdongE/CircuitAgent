package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.circuitagent.design.model.*;
import java.util.*;
import java.time.Instant;

@Entity
@Table(name = "part_specification")
public class PartSpecificationEntity {
    @Id UUID id;
    @Column(length = 512) String manufacturer;
    @Column(length = 512) String mpn;
    @Column(name = "component_type", length = 128) String type;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "json") Map<String, AttributeValue> attributes;
    protected PartSpecificationEntity() {}
}
