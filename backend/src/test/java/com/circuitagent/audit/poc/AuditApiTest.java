package com.circuitagent.audit.poc;
import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.persistence.CircuitDesignStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@AutoConfigureMockMvc
class AuditApiTest extends MySqlIntegrationSupport {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired CircuitDesignStore designs;@Autowired PocRuleCatalog catalog;
    @Test void actualRulesExecutePersistAndReloadEvidenceHistoryAndExactCalculatedString() throws Exception {
        var g=SnapshotContractTest.real();designs.save(g.designSnapshot(),List.of());String root="/api/designs/"+g.designId()+"/audits";
        var input=new AuditService.Request("poc-rule-v1",catalog.rules().stream().map(AuditRule::id).toList(),null);
        String body=mvc.perform(post(root).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input))).andExpect(status().isCreated()).andExpect(jsonPath("$.results.length()").value(6)).andReturn().getResponse().getContentAsString();
        var node=json.readTree(body);String id=node.get("id").asText();
        assertThat(node.get("results").get(2).get("measured").asText()).startsWith("11.961581395348837209");
        mvc.perform(get(root+"/"+id)).andExpect(status().isOk()).andExpect(content().json(body));
        mvc.perform(get(root+"/"+id+"/results")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("UNKNOWN")).andExpect(jsonPath("$[0].evidence.components.R107.assemblyStatus").value("DNP"));
        mvc.perform(get(root)).andExpect(status().isOk()).andExpect(jsonPath("$[0].counts.PASS").value(1)).andExpect(jsonPath("$[0].counts.UNKNOWN").value(5));
        mvc.perform(get("/api/designs/"+UUID.randomUUID()+"/audits/"+id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/audit-rules")).andExpect(status().isOk()).andExpect(jsonPath("$.pinMapping.FB").value("4"));
    }
    @Test void ruleFailIsSuccessfulHttpAuditAndMalformedOrMissingRequestsAreExplicit() throws Exception {
        var g=RuleCheckerTest.own("Reference,Value,DNP\nR1,1k,no\n","NET : 'x' R1-1\n");designs.save(g.designSnapshot(),List.of());String root="/api/designs/"+g.designId()+"/audits";
        var input=new AuditService.Request("poc-rule-v1",null,List.of(RuleInputTest.range("own-fail","Ω","2000","3000")));
        mvc.perform(post(root).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input))).andExpect(status().isCreated()).andExpect(jsonPath("$.results[0].status").value("FAIL"));
        mvc.perform(post(root).contentType(MediaType.APPLICATION_JSON).content("{\"schemaVersion\":\"poc-rule-v1\",\"ruleIds\":[\"wrong\"]}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RULE_INPUT_INVALID"));
        mvc.perform(post(root).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(root).contentType(MediaType.APPLICATION_JSON).content("{\"schemaVersion\":\"poc-rule-v1\",\"ruleIds\":[\"tps54302-c5-nominal\"],\"unknownOperator\":\"GT\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/designs/"+UUID.randomUUID()+"/audits").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input))).andExpect(status().isNotFound());
    }
}
