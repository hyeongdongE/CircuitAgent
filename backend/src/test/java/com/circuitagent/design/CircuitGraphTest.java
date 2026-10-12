package com.circuitagent.design;

// CircuitGraphTest: 대상 기능의 정상 동작과 오류 경계를 검증한다. 입력과 기대값은 각 테스트 메서드에 명시한다.

import static org.assertj.core.api.Assertions.*;

import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CircuitGraphTest {
  static CircuitDesign real5v() throws Exception {
    return new CircuitAssembler()
        .assemble(
            "5V Power Supply",
            "v1",
            new BomParser().parse(BomParserTest.fixture("5v/5Volt_Power_Supply.csv"), "bom.csv"),
            new KiCadNetlistParser()
                .parse(BomParserTest.fixture("5v/5Volt_Power_Supply.net"), "5v.net"));
  }

  @Test
  void real5vAttributesAndConnectivityAreQueryableTogetherWithoutMergingNets() throws Exception {
    var design = real5v();
    var graph = new CircuitGraph(design);
    assertThat(design.components()).hasSize(12);
    assertThat(design.issues()).isEmpty();
    assertThat(graph.getComponent("R1").value()).isEqualTo("1k");
    assertThat(graph.getComponentAttributes("R1").get("Resistance").normalized())
        .isEqualByComparingTo("1000");
    assertThat(graph.getComponent("R1").rawSources()).containsKeys("bom", "netlist");
    assertThat(graph.getComponentPins("R1")).hasSize(2);
    assertThat(graph.getNetOfPin("R1", "1").orElseThrow().id()).isEqualTo("6");
    assertThat(graph.getNetOfPin("R1", "2").orElseThrow().id()).isEqualTo("5");
    assertThat(graph.getComponentsOnNet("6"))
        .extracting(ComponentInstance::reference)
        .containsExactlyInAnyOrder("C3", "J1", "R1", "U1");
    assertThat(graph.getComponentsOnNet("5"))
        .extracting(ComponentInstance::reference)
        .containsExactlyInAnyOrder("D5", "R1");
    assertThat(graph.getConnectedPins("1")).hasSize(8);
    assertThat(graph.getComponentAttributes("U1")).doesNotContainKey("Voltage Rating");
    assertThatThrownBy(() -> graph.getNetOfPin("R1", "99"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void realShiftMatchesAll27BomReferencesAndReportsFourExtraMountingHoles() throws Exception {
    var bom =
        new BomParser()
            .parse(BomParserTest.fixture("shift/Shift_Indicator_PCB_BOM.csv"), "shift.csv");
    var net =
        new KiCadNetlistParser()
            .parse(BomParserTest.fixture("shift/Shift_Indicator_PCB.net"), "shift.net");
    var design = new CircuitAssembler().assemble("Shift Indicator", "v1", bom, net);
    assertThat(net.components().keySet()).containsAll(bom.components().keySet());
    assertThat(design.components()).hasSize(31);
    assertThat(
            design.issues().stream()
                .filter(i -> i.code() == ImportIssue.Code.NETLIST_ONLY_COMPONENT))
        .extracting(ImportIssue::reference)
        .containsExactlyInAnyOrder("H1", "H2", "H3", "H4");
    assertThat(design.issues()).noneMatch(i -> i.code() == ImportIssue.Code.BOM_ONLY_COMPONENT);
    assertThat(new CircuitGraph(design).getComponentPins("H1")).isEmpty();
  }

  @Test
  void bomOnlyReferencesAndValueConflictsRemainVisible() throws Exception {
    var source = real5v();
    var c = source.components().get("R1");
    var edited = new HashMap<>(source.components());
    edited.put(
        "R1",
        new ComponentInstance("R1", "2k", c.partSpecificationId(), c.rawSources(), c.sourcePath()));
    edited.put("R99", new ComponentInstance("R99", "1k", c.partSpecificationId(), Map.of(), null));
    var bom = new BomResult(edited, source.specifications(), List.of());
    var net =
        new KiCadNetlistParser()
            .parse(BomParserTest.fixture("5v/5Volt_Power_Supply.net"), "5v.net");
    var design = new CircuitAssembler().assemble("conflict", "v2", bom, net);
    assertThat(design.issues())
        .extracting(ImportIssue::code)
        .contains(ImportIssue.Code.VALUE_MISMATCH, ImportIssue.Code.BOM_ONLY_COMPONENT);
    assertThat(new CircuitGraph(design).getComponentPins("R99")).isEmpty();
    assertThat(new CircuitGraph(design).getComponent("R1").value()).isEqualTo("2k");
  }
}
