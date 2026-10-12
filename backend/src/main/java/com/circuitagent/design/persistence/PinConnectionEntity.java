package com.circuitagent.design.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.circuitagent.design.model.*;
import java.util.*;
import java.time.Instant;

@Entity
@Table(name = "pin_connection")
public class PinConnectionEntity {
    @Id UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "design_id") CircuitDesignEntity design;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pin_id", unique = true) ComponentPinEntity pin;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "net_id") CircuitNetEntity net;
    protected PinConnectionEntity() {}
}
