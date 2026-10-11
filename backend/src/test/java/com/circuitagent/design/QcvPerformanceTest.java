package com.circuitagent.design;

// QcvPerformanceTest: 실제 공개 Fixture의 회로 사실과 파싱 결과를 검증한다.

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.parse.*;
import com.circuitagent.design.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class QcvPerformanceTest extends MySqlIntegrationSupport {
  @Autowired CircuitDesignStore store;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private static double millis(long start) {
    return (System.nanoTime() - start) / 1_000_000.0;
  }

  @Test
  void measuresActualFePipelinePhasesWithoutClaimingAnSla() throws Exception {
    var samples = new ArrayList<Map<String, Double>>();
    for (int run = 0; run < 3; run++) {
      var m = new LinkedHashMap<String, Double>();
      byte[] xlsx = QcvFixtures.file("fe", ".xlsx"), qcv = QcvFixtures.file("fe", ".qcv");
      long start = System.nanoTime();
      var bom = new BomParser().parse(xlsx, "BOM.xlsx");
      m.put("bomParsingMs", millis(start));
      start = System.nanoTime();
      var net = new QcvNetlistParser().parse(qcv, "Netlist.qcv");
      m.put("qcvParsingMs", millis(start));
      start = System.nanoTime();
      var design =
          new CircuitAssembler().assemble("benchmark-" + UUID.randomUUID(), "R3", bom, net);
      m.put("integrationMs", millis(start));
      start = System.nanoTime();
      var graph = new CircuitGraph(design);
      m.put("graphConstructionMs", millis(start));
      assertThat(graph.getNetOfPin("U16", "5").orElseThrow().name()).isEqualTo("U16_EN");
      start = System.nanoTime();
      store.save(
          design,
          List.of(
              new SourceFile("bom", "BOM.xlsx", xlsx),
              new SourceFile("netlist", "Netlist.qcv", qcv)));
      m.put("mysqlPersistenceMs", millis(start));
      start = System.nanoTime();
      mvc.perform(get("/api/designs/" + design.id() + "/components/U16"))
          .andExpect(status().isOk())
          .andReturn()
          .getResponse()
          .getContentAsByteArray();
      m.put("apiRetrievalMs", millis(start));
      samples.add(m);
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("board", "FE R3: BOM445 + PIN-only8, nets335, connections1500");
    report.put("java", System.getProperty("java.version"));
    report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
    report.put("database", "Docker/Testcontainers MySQL 8.0.43");
    report.put(
        "method",
        "3 sequential JVM samples, nanoTime. File reads and container/Spring startup excluded. Persistence includes both raw files. MockMvc API retrieval includes full DB load, graph/view/JSON serialization; excludes network sockets. No SLA assertion.");
    report.put("samples", samples);
    Map<String, Double> medians = new LinkedHashMap<>();
    for (String phase : samples.getFirst().keySet())
      medians.put(phase, samples.stream().mapToDouble(s -> s.get(phase)).sorted().toArray()[1]);
    report.put("medianMs", medians);
    Files.writeString(
        QcvFixtures.dir().resolve("performance.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    System.out.println("PERFORMANCE_MS=" + medians);
  }
}
