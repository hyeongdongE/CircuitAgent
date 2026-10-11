package com.circuitagent.design;

import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.model.*;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.parse.*;
import com.circuitagent.design.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MySqlPersistenceTest extends MySqlIntegrationSupport {
    @Autowired CircuitDesignStore store;
    @Autowired JdbcTemplate jdbc;
    private static List<SourceFile> files5v() throws Exception {
        return List.of(new SourceFile("bom", "bom.csv", BomParserTest.fixture("5v/5Volt_Power_Supply.csv")),
            new SourceFile("netlist", "5v.net", BomParserTest.fixture("5v/5Volt_Power_Supply.net")));
    }
    @Test
    void realCircuitRoundTripsThroughNormalizedMySqlTablesAndJson() throws Exception {
        var original = CircuitGraphTest.real5v();
        store.save(original, files5v());
        var restored = store.load(original.id());
        assertThat(restored).usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(original);
        assertThat(restored.components()).hasSize(12);
        assertThat(restored.pins()).hasSize(25);
        assertThat(restored.nets()).hasSize(6);
        assertThat(restored.connections()).hasSize(25);
        assertThat(new CircuitGraph(restored).getComponentAttributes("R1").get("Resistance").normalized()).isEqualByComparingTo("1000");
        var storedFiles = store.sourceFiles(original.id());
        assertThat(storedFiles).hasSize(2);
        for (var expected : files5v()) {
            var actual = storedFiles.stream().filter(f -> f.role().equals(expected.role())).findFirst().orElseThrow();
            assertThat(actual.content()).containsExactly(expected.content());
            assertThat(actual.sha256()).isEqualTo(expected.sha256());
        }
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = database() and data_type = 'json'", Integer.class)).isEqualTo(3);
    }
    @Test
    void shiftIssuesAndReusedSpecificationsSurvivePersistence() throws Exception {
        var model = new CircuitAssembler().assemble("Shift persistence", "v1",
            new BomParser().parse(BomParserTest.fixture("shift/Shift_Indicator_PCB_BOM.csv"), "shift.csv"),
            new KiCadNetlistParser().parse(BomParserTest.fixture("shift/Shift_Indicator_PCB.net"), "shift.net"));
        store.save(model, List.of());
        var restored = store.load(model.id());
        assertThat(restored).usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(model);
        assertThat(restored.issues().stream().filter(i -> i.code() == ImportIssue.Code.NETLIST_ONLY_COMPONENT)).hasSize(4);
        assertThat(restored.components().get("R3").partSpecificationId()).isEqualTo(restored.components().get("R9").partSpecificationId());
    }
    @Test
    void versionsAreIsolatedAndTransactionRollsBackOnLateConstraintFailure() throws Exception {
        var model = CircuitGraphTest.real5v();
        var other = new CircuitDesign(UUID.randomUUID(), "5V rollback test", "v2", model.components(), model.specifications(), model.pins(), model.nets(), model.connections(), model.issues(), model.sourceMetadata());
        var source = files5v().getFirst();
        assertThatThrownBy(() -> store.save(other, List.of(source, source))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.load(other.id())).isInstanceOf(NoSuchElementException.class);
        store.save(other, files5v());
        var v3 = new CircuitDesign(UUID.randomUUID(), other.name(), "v3", model.components(), model.specifications(), model.pins(), model.nets(), model.connections(), model.issues(), model.sourceMetadata());
        store.save(v3, files5v());
        assertThat(store.load(other.id()).version()).isEqualTo("v2");
        assertThat(store.load(v3.id()).version()).isEqualTo("v3");
        var duplicate = new CircuitDesign(UUID.randomUUID(), other.name(), "v2", model.components(), model.specifications(), model.pins(), model.nets(), model.connections(), model.issues(), model.sourceMetadata());
        assertThatThrownBy(() -> store.save(duplicate, files5v())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.load(duplicate.id())).isInstanceOf(NoSuchElementException.class);
    }
}
