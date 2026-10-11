package com.circuitagent.design.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CircuitDesignRepository extends JpaRepository<CircuitDesignEntity, UUID> {}
