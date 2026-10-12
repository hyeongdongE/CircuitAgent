package com.circuitagent.audit.poc;

import com.circuitagent.design.persistence.CircuitDesignRepository;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit 실행과 결과의 JPA 저장/복원 경계. 설계 ID와 복합 FK로 데이터가 섞이지 않게 한다. metadata/payload JSON은 문자열 envelope로
 * 저장해 MySQL 숫자의 정밀도 손실을 막는다. 사용자별 소유권 검사는 여기의 설계 ID 격리와 별개다.
 */
@Repository
public class AuditStore {
  @PersistenceContext EntityManager em;
  private final CircuitDesignRepository designs;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public AuditStore(
      CircuitDesignRepository designs, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.designs = designs;
    this.json = json;
  }

  // 실행 헤더와 모든 결과를 하나의 트랜잭션으로 저장한다. 중간 unique/FK 실패는 전체 롤백이다.
  @Transactional
  public void save(AuditExecution value) {
    if (!value.snapshotHash().matches("[0-9a-f]{64}")
        || value.ruleSnapshot().size() != value.results().size())
      throw new IllegalArgumentException("Invalid execution snapshot");
    var e = new AuditExecutionEntity();
    e.id = value.id();
    e.designId = value.designId();
    e.designVersion = value.designVersion();
    e.snapshotHash = value.snapshotHash();
    e.executedAt = value.executedAt();
    e.metadata =
        encode(
            new AuditExecution(
                value.id(),
                value.designId(),
                value.designVersion(),
                value.snapshotHash(),
                value.ruleSnapshot(),
                List.of(),
                value.executedAt(),
                value.timingMs()));
    em.persist(e);
    for (int i = 0; i < value.results().size(); i++) {
      var v = value.results().get(i);
      var rule = value.ruleSnapshot().get(i);
      // 결과가 해당 Rule 사본의 ID/version/Reference와 정확히 대응하는지 저장 전에 검사한다.
      if (!rule.id().equals(v.ruleId())
          || !rule.version().equals(v.ruleVersion())
          || !rule.target().reference().equals(v.reference()))
        throw new IllegalArgumentException("Rule snapshot/result mismatch");
      var r = new AuditResultEntity();
      r.id = v.id();
      r.executionId = value.id();
      r.designId = value.designId();
      r.resultOrder = i;
      r.ruleId = v.ruleId();
      r.ruleVersion = v.ruleVersion();
      r.componentReference = v.reference();
      r.status = v.status();
      r.payload = encode(v);
      em.persist(r);
    }
    em.flush();
  }

  // 존재하는 audit ID여도 요청 designId와 다르면404다. 이 검사는 데이터 격리이며 사용자 인증은 아니다.
  @Transactional(readOnly = true)
  public AuditExecution load(UUID designId, UUID id) {
    var row = em.find(AuditExecutionEntity.class, id);
    if (row == null || !row.designId.equals(designId))
      throw new NoSuchElementException("Audit not found");
    var meta = decode(row.metadata, AuditExecution.class);
    // 큰 JSON을 DB filesort에 넣지 않고 최대100개 결과를 Java에서 resultOrder로 정렬한다.
    var results =
        em
            .createQuery(
                "select r from AuditResultEntity r where r.executionId=:id and r.designId=:design",
                AuditResultEntity.class)
            .setParameter("id", id)
            .setParameter("design", designId)
            .getResultList()
            .stream()
            .sorted(Comparator.comparingInt(r -> r.resultOrder))
            .map(r -> decode(r.payload, AuditResult.class))
            .toList();
    return new AuditExecution(
        meta.id(),
        meta.designId(),
        meta.designVersion(),
        meta.snapshotHash(),
        meta.ruleSnapshot(),
        results,
        meta.executedAt(),
        meta.timingMs());
  }

  // JSON 내부 숫자를 문자열 envelope 안에 보존한다. DECIMAL128 값과 시간의 nanos가 MySQL 변환으로 줄지 않는다.
  private Map<String, String> encode(Object value) {
    try {
      return Map.of("json", json.writeValueAsString(value));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Audit serialization failed", e);
    }
  }

  private <T> T decode(Map<String, String> value, Class<T> type) {
    try {
      return json.readValue(value.get("json"), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Audit restoration failed", e);
    }
  }

  public record Summary(
      UUID id,
      UUID designId,
      String designVersion,
      java.time.Instant executedAt,
      Map<AuditStatus, Long> counts) {}

  // 이력은 최신50개로 제한하고 상태 count만 조회한다. 전체 Evidence는 상세 API에서 읽는다.
  @Transactional(readOnly = true)
  public List<Summary> history(UUID designId) {
    if (!designs.existsById(designId)) throw new NoSuchElementException("Design not found");
    return em
        .createQuery(
            "select e from AuditExecutionEntity e where e.designId=:id order by e.executedAt desc",
            AuditExecutionEntity.class)
        .setParameter("id", designId)
        .setMaxResults(50)
        .getResultList()
        .stream()
        .map(
            e -> {
              var counts = new EnumMap<AuditStatus, Long>(AuditStatus.class);
              em.createQuery(
                      "select r.status, count(r) from AuditResultEntity r where r.executionId=:id group by r.status",
                      Object[].class)
                  .setParameter("id", e.id)
                  .getResultList()
                  .forEach(x -> counts.put((AuditStatus) x[0], (Long) x[1]));
              return new Summary(
                  e.id, e.designId, e.designVersion, e.executedAt, Map.copyOf(counts));
            })
        .toList();
  }
}
