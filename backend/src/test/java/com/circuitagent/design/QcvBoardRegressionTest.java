package com.circuitagent.design;

// QcvBoardRegressionTest: 실제 공개 Fixture의 회로 사실과 파싱 결과를 검증한다.

import static org.assertj.core.api.Assertions.*;

import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import com.circuitagent.design.persistence.*;
import com.circuitagent.design.tools.CsvToXlsx;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

class QcvBoardRegressionTest extends MySqlIntegrationSupport {
  @Autowired CircuitDesignStore store;

  @ParameterizedTest
  @CsvSource({
    "fe,69,445,335,1500,143,135,51,445",
    "ntop,55,296,233,972,147,139,67,292",
    "fe-ntop,21,161,193,794,25,17,37,161"
  })
  void allThreeRealBoardsPreserveExcelNetlistTopologyAndMySql(
      String b,
      int rows,
      int bomCount,
      int nets,
      int connected,
      int declared,
      int term,
      int nm,
      int netRefs)
      throws Exception {
    var csv = CsvToXlsx.rows(QcvFixtures.file(b, ".csv"));
    assertThat(csv).hasSize(rows + 1);
    try (var excel =
        WorkbookFactory.create(new ByteArrayInputStream(QcvFixtures.file(b, ".xlsx")))) {
      var sheet = excel.getSheetAt(0);
      assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(csv.size());
      for (int i = 0; i < csv.size(); i++)
        for (int j = 0; j < csv.get(i).size(); j++)
          assertThat(sheet.getRow(i).getCell(j).getStringCellValue()).isEqualTo(csv.get(i).get(j));
    }
    var bom = new BomParser().parse(QcvFixtures.file(b, ".xlsx"), b + ".xlsx");
    var net = new QcvNetlistParser().parse(QcvFixtures.file(b, ".qcv"), b + ".qcv");
    assertThat(bom.components()).hasSize(bomCount);
    assertThat(net.components().keySet()).containsAll(bom.components().keySet());
    assertThat(net.nets()).hasSize(nets);
    assertThat(net.connections()).hasSize(connected);
    assertThat(net.connections().stream().map(c -> c.pin().reference()).collect(Collectors.toSet()))
        .hasSize(netRefs);
    assertThat(net.sourceMetadata())
        .containsEntry("separatePinRecords", Integer.toString(declared));
    assertThat(net.pins().stream().filter(p -> p.disposition() == PinDisposition.TERMINATED))
        .hasSize(term);
    assertThat(
            bom.components().values().stream()
                .filter(c -> c.assemblyStatus() == AssemblyStatus.DNP))
        .hasSize(nm);
    assertThat(net.issues()).isEmpty();
    var model =
        new CircuitAssembler()
            .assemble("regression-" + b + "-" + UUID.randomUUID(), "R3", bom, net);
    assertThat(model.components()).hasSize(bomCount + 8);
    var graph = new CircuitGraph(model);
    // Compare three complete NET terminal lists against original source, not counts alone.
    for (String line :
        new String(QcvFixtures.file(b, ".qcv"), StandardCharsets.UTF_8)
            .lines()
            .filter(l -> l.startsWith("NET : '"))
            .limit(3)
            .toList()) {
      int quote = line.indexOf('\'', 7);
      String label = line.substring(line.indexOf('\'') + 1, quote);
      var expected =
          Arrays.stream(line.substring(quote + 1).trim().split("\\s+"))
              .map(
                  t ->
                      new PinKey(
                          t.substring(0, t.lastIndexOf('-')), t.substring(t.lastIndexOf('-') + 1)))
              .toList();
      var parsed =
          net.nets().values().stream()
              .filter(n -> n.name().equals(label))
              .findFirst()
              .orElseThrow();
      assertThat(graph.getConnectedPins(parsed.id()).stream().map(ComponentPin::key))
          .containsExactlyInAnyOrderElementsOf(expected);
    }
    if (b.equals("ntop"))
      for (String ref : List.of("M1", "M2", "M3", "M4")) {
        assertThat(bom.components()).containsKey(ref);
        assertThat(graph.getComponentPins(ref)).isNotEmpty();
        assertThat(graph.getComponentPins(ref))
            .allMatch(p -> graph.getNetOfPin(ref, p.key().number()).isEmpty());
      }
    store.save(model, QcvPersistenceTest.files(b));
    assertThat(store.load(model.id()))
        .usingRecursiveComparison()
        .ignoringCollectionOrder()
        .isEqualTo(model);
    System.out.println(
        b
            + "_ISSUES="
            + model.issues().stream()
                .collect(Collectors.groupingBy(ImportIssue::code, Collectors.counting())));
  }
}
