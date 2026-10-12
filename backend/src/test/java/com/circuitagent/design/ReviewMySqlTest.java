package com.circuitagent.design;

// ReviewMySqlTest: Testcontainers MySQL에서 저장·복원 또는 스키마 제약을 검증한다. 의도적인 실패 입력은 무결성과 롤백 확인용이다.

import static org.assertj.core.api.Assertions.*;

import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import com.circuitagent.design.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class ReviewMySqlTest extends MySqlIntegrationSupport {
  @Autowired CircuitDesignStore store;
  @Autowired DesignQueryService queries;
  @Autowired JdbcTemplate jdbc;

  static CircuitDesign renamed(CircuitDesign m) {
    return new CircuitDesign(
        UUID.randomUUID(),
        "review-" + UUID.randomUUID(),
        "v1",
        m.components(),
        m.specifications(),
        m.pins(),
        m.nets(),
        m.connections(),
        m.issues(),
        m.sourceMetadata());
  }

  @Test
  void unreferencedSpecificationsAndPlacementSurviveRoundTrip() throws Exception {
    var m =
        ReviewRegressionTest.model(
            "Reference,Value,DNP\nR1,1k,1\n", "(export (components (comp (ref R1))) (nets))");
    var specs = new HashMap<>(m.specifications());
    var extra = new PartSpecification(UUID.randomUUID(), "Vendor", "unused", null, Map.of());
    specs.put(extra.id(), extra);
    var withExtra =
        new CircuitDesign(
            m.id(),
            m.name(),
            m.version(),
            m.components(),
            specs,
            m.pins(),
            m.nets(),
            m.connections(),
            m.issues(),
            m.sourceMetadata());
    store.save(withExtra, List.of());
    assertThat(store.load(m.id()))
        .usingRecursiveComparison()
        .ignoringCollectionOrder()
        .isEqualTo(withExtra);
    assertThat(queries.getComponent(m.id(), "R1").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
  }

  @Test
  void designScopedQueriesAndDistinctAttributesAreIsolated() throws Exception {
    var a = renamed(CircuitGraphTest.real5v());
    store.save(a, List.of());
    var modifiedBom =
        new String(
                BomParserTest.fixture("5v/5Volt_Power_Supply.csv"),
                java.nio.charset.StandardCharsets.UTF_8)
            .replace("1k", "2k");
    var b =
        new CircuitAssembler()
            .assemble(
                "review-" + UUID.randomUUID(),
                "v1",
                ReviewRegressionTest.bom(modifiedBom),
                new KiCadNetlistParser()
                    .parse(BomParserTest.fixture("5v/5Volt_Power_Supply.net"), "real.net"));
    store.save(b, List.of());
    assertThat(queries.getComponentAttributes(a.id(), "R1").get("Resistance").normalized())
        .isEqualByComparingTo("1000");
    assertThat(queries.getComponentAttributes(b.id(), "R1").get("Resistance").normalized())
        .isEqualByComparingTo("2000");
    assertThat(queries.getComponentPins(a.id(), "R1")).hasSize(2);
    assertThat(queries.getNetOfPin(a.id(), "R1", "1").orElseThrow().id()).isEqualTo("6");
    assertThat(queries.getConnectedPins(a.id(), "6")).hasSize(4);
    assertThat(queries.getComponentsOnNet(a.id(), "6"))
        .extracting(ComponentInstance::reference)
        .contains("U1");
    var snapshot = queries.snapshot(a.id());
    assertThat(snapshot.designId()).isEqualTo(a.id());
    assertThat(snapshot.explore(new PinKey("R1", "1"), Set.of("R1"), 3).nets())
        .containsExactlyInAnyOrder("5", "6");
    assertThat(snapshot.getComponentIssues("R1")).isEmpty();
    assertThat(queries.snapshot(b.id()).getComponentIssues("R1"))
        .extracting(ImportIssue::code)
        .contains(ImportIssue.Code.VALUE_MISMATCH);
    assertThatThrownBy(() -> queries.snapshot(UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void mysqlRejectsBothCrossDesignPinAndNetForeignKeys() throws Exception {
    var a = renamed(CircuitGraphTest.real5v());
    var b = renamed(CircuitGraphTest.real5v());
    store.save(a, List.of());
    store.save(b, List.of());
    var row =
        jdbc.queryForMap(
            "select id,design_id,pin_id,net_id from pin_connection where design_id=UUID_TO_BIN(?) limit 1",
            a.id().toString());
    var other =
        jdbc.queryForMap(
            "select design_id,net_id from pin_connection where design_id=UUID_TO_BIN(?) limit 1",
            b.id().toString());
    jdbc.update(
        "delete from pin_connection where id=?",
        row.get("id")); // free pin so uniqueness cannot mask FK failure
    try {
      jdbc.update(
          "insert into pin_connection(id,design_id,pin_id,net_id) values(UUID_TO_BIN(?),?,?,?)",
          UUID.randomUUID().toString(),
          other.get("design_id"),
          row.get("pin_id"),
          other.get("net_id"));
      fail("Cross-design pin must be rejected");
    } catch (DataIntegrityViolationException e) {
      assertThat(e.getMostSpecificCause().getMessage()).contains("fk_connection_pin_design");
    }
    try {
      jdbc.update(
          "insert into pin_connection(id,design_id,pin_id,net_id) values(UUID_TO_BIN(?),?,?,?)",
          UUID.randomUUID().toString(),
          row.get("design_id"),
          row.get("pin_id"),
          other.get("net_id"));
      fail("Cross-design net must be rejected");
    } catch (DataIntegrityViolationException e) {
      assertThat(e.getMostSpecificCause().getMessage()).contains("fk_connection_net_design");
    }
  }
}
