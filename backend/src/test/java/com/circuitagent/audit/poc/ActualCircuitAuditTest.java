package com.circuitagent.audit.poc;
import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@AutoConfigureMockMvc
class ActualCircuitAuditTest extends MySqlIntegrationSupport {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired DesignQueryService queries;@Autowired PocRuleCatalog catalog;@Autowired DeterministicRuleChecker checker;
    @Test void realUploadStoreSnapshotAndSixDatasheetRulesExposeActualFactsAndUnknowns() throws Exception {
        var dir=Path.of(System.getProperty("qcv.fixture.dir"));
        String body=mvc.perform(multipart("/api/designs/import").file(new MockMultipartFile("bom","BOM.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",Files.readAllBytes(dir.resolve("fe.xlsx"))))
            .file(new MockMultipartFile("netlist","Netlist.qcv","text/plain",Files.readAllBytes(dir.resolve("fe.qcv")))).param("name","Real audit "+UUID.randomUUID()).param("version","R3"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.componentCount").value(453)).andExpect(jsonPath("$.connectionCount").value(1500)).andReturn().getResponse().getContentAsString();
        var graph=queries.snapshot(UUID.fromString(mapper.readTree(body).get("id").asText()));
        var results=catalog.rules().stream().map(r->checker.evaluate(graph,r)).toList();
        assertThat(results.stream().filter(r->r.status()==AuditStatus.PASS)).hasSize(1);
        assertThat(results.stream().filter(r->r.status()==AuditStatus.UNKNOWN)).hasSize(5);
        var en=results.stream().filter(r->r.ruleId().equals("tps54302-u16-en-network")).findFirst().orElseThrow();
        assertThat(en.evidence().components().get("R107").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        assertThat(en.evidence().components().get("R83").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        var fb=results.stream().filter(r->r.ruleId().equals("tps54302-u16-fb-nominal")).findFirst().orElseThrow();
        assertThat(fb.evidence().components()).containsKeys("U16","R82","R87");assertThat(fb.measured().toPlainString()).startsWith("11.961581395");
        assertThat(results).allSatisfy(r->assertThat(r.evidence().datasheet().sha256()).isEqualTo(RuleInputTest.SOURCE.sha256()));
        Files.writeString(dir.resolve("actual-audit-results.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
}
