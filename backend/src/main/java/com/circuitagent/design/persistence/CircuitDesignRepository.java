package com.circuitagent.design.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 설계 존재 여부와 기본 PK 조회를 제공하는 Spring Data JPA 계약. 전체 그래프 저장/복원은 CircuitDesignStore에서 관계와 트랜잭션을 묶어
 * 처리한다.
 */
public interface CircuitDesignRepository extends JpaRepository<CircuitDesignEntity, UUID> {}
