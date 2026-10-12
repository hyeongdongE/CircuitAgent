package com.circuitagent.design;

import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class QcvGraphTest {
    @Test void u16NeighborhoodReportsFactsAndNeverMergesResistorNets() throws Exception {
        var model=QcvIntegrationTest.board("fe"); var graph=new CircuitGraph(model);
        var en=graph.getNetOfPin("U16","5").orElseThrow();
        assertThat(en.name()).isEqualTo("U16_EN");
        assertThat(graph.getConnectedPins(en.id())).extracting(ComponentPin::key).containsExactlyInAnyOrder(new PinKey("U16","5"),new PinKey("R107","2"),new PinKey("R83","2"));
        assertThat(graph.getNetOfPin("R107","1").orElseThrow().name()).isEqualTo("VDD");
        assertThat(graph.getNetOfPin("R83","1").orElseThrow().name()).isEqualTo("GND");
        var defaultMode=graph.explore(new PinKey("U16","5"),Set.of("R107","R83"),2);
        assertThat(defaultMode.edges()).noneMatch(e->e.kind()==CircuitGraph.EdgeKind.COMPONENT);
        var schematic=graph.explore(new PinKey("U16","5"),Set.of("R107","R83"),2,CircuitGraph.TraversalMode.INCLUDE_DNP_SCHEMATIC);
        assertThat(schematic.pins()).contains(new PinKey("R107","1"),new PinKey("R83","1"));
        assertThat(schematic.edges()).anyMatch(e->e.kind()==CircuitGraph.EdgeKind.COMPONENT&&e.from().reference().equals("R107")&&e.sourcePath().startsWith("sheet:BOM:row:"));
        assertThat(schematic.edges()).anyMatch(e->e.kind()==CircuitGraph.EdgeKind.NET&&e.sourcePath().contains(".qcv:line:"));
        assertThat(model.nets()).hasSize(335);
        assertThat(graph.getComponentAttributes("R107").get("Resistance").normalized()).isEqualByComparingTo("140000");
        System.out.println("FE_ISSUES="+model.issues().stream().collect(Collectors.groupingBy(ImportIssue::code,Collectors.counting())));
    }
    @Test void explicitTerminatedAndUnconnectedPinsRemainDifferentFromUnknownInventory() throws Exception {
        var graph=new CircuitGraph(QcvIntegrationTest.board("fe"));
        assertThat(graph.getPinContext("U29","5").status()).isEqualTo(CircuitGraph.ConnectionStatus.TERMINATED);
        assertThat(graph.getNetOfPin("U29","5")).isEmpty();
        assertThat(graph.getPinContext("PCB1","1").status()).isEqualTo(CircuitGraph.ConnectionStatus.UNCONNECTED);
        assertThat(graph.getPinContext("U16","99").status()).isEqualTo(CircuitGraph.ConnectionStatus.MISSING_PIN);
        assertThat(graph.getPinContext("U16","5").pin().name()).isNull(); // no pin function inferred from Net name
        assertThat(graph.getPinContext("U16","5").pin().sourceMetadata()).containsKey("filename");
    }
}
