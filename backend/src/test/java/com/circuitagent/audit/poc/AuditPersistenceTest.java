package com.circuitagent.audit.poc;

// AuditPersistenceTest: Testcontainers MySQL에서 저장·복원 또는 스키마 제약을 검증한다. 의도적인 실패 입력은 무결성과 롤백 확인용이다.

import static org.assertj.core.api.Assertions.*;

import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.persistence.CircuitDesignStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditPersistenceTest extends MySqlIntegrationSupport {
  @Autowired AuditRunner runner;
  @Autowired AuditStore audits;
  @Autowired CircuitDesignStore designs;
  @Autowired PocRuleCatalog catalog;
  @Autowired JdbcTemplate jdbc;

  @Test
  void realAuditRoundtripPreservesRulesEvidenceHashAndDesignIsolation() throws Exception {
    var g = SnapshotContractTest.real();
    designs.save(g.designSnapshot(), List.of());
    var execution = runner.run(g, catalog.rules());
    audits.save(execution);
    assertThat(audits.load(g.designId(), execution.id()))
        .usingRecursiveComparison()
        .isEqualTo(execution);
    assertThat(runner.hash(new CircuitGraph(designs.load(g.designId()))))
        .isEqualTo(execution.snapshotHash());
    assertThat(audits.history(g.designId())).hasSize(1);
    assertThatThrownBy(() -> audits.load(UUID.randomUUID(), execution.id()))
        .isInstanceOf(NoSuchElementException.class);
    // Stored immutable rule/version/source snapshot survives caller creating later rules.
    assertThat(
            audits.load(g.designId(), execution.id()).ruleSnapshot().getFirst().source().revision())
        .contains("SLVSDG6C");
  }

  @Test
  void lateResultFailureRollsBackExecutionAndForeignDesignFkRejects() throws Exception {
    var g = SnapshotContractTest.real();
    designs.save(g.designSnapshot(), List.of());
    var r = runner.run(g, catalog.rules());
    var first = r.results().getFirst();
    var duplicate =
        new AuditResult(
            UUID.randomUUID(),
            first.ruleId(),
            first.ruleVersion(),
            first.reference(),
            first.status(),
            first.measured(),
            first.expected(),
            first.unit(),
            first.reason(),
            first.missingInputs(),
            first.evidence(),
            first.executedAt());
    var rows = new ArrayList<>(r.results());
    rows.set(1, duplicate);
    var rules = new ArrayList<>(r.ruleSnapshot());
    rules.set(1, rules.getFirst());
    var bad =
        new AuditExecution(
            r.id(),
            r.designId(),
            r.designVersion(),
            r.snapshotHash(),
            rules,
            rows,
            r.executedAt(),
            r.timingMs());
    assertThatThrownBy(() -> audits.save(bad)).isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_execution where id=UUID_TO_BIN(?)",
                Integer.class,
                r.id().toString()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_result where execution_id=UUID_TO_BIN(?)",
                Integer.class,
                r.id().toString()))
        .isZero();
    audits.save(r);
    assertThat(audits.load(g.designId(), r.id()).results()).hasSize(6);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update audit_result set design_id=UUID_TO_BIN(?) where execution_id=UUID_TO_BIN(?)",
                    UUID.randomUUID().toString(),
                    r.id().toString()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
