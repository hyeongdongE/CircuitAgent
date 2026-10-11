package com.circuitagent.design;

import com.circuitagent.MySqlIntegrationSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@AutoConfigureMockMvc
class DesignApiTest extends MySqlIntegrationSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private MockMultipartFile bom5v() throws Exception { return new MockMultipartFile("bom", "bom.csv", "text/csv", BomParserTest.fixture("5v/5Volt_Power_Supply.csv")); }
    private MockMultipartFile net5v() throws Exception { return new MockMultipartFile("netlist", "5v.net", "text/plain", BomParserTest.fixture("5v/5Volt_Power_Supply.net")); }
    @Test
    void uploadsRealFilesPersistsAndReturnsAttributesPinsAndNets() throws Exception {
        String body = mvc.perform(multipart("/api/designs/import").file(bom5v()).file(net5v()).param("name", "API 5V").param("version", "v1"))
            .andExpect(status().isCreated()).andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.componentCount").value(12)).andExpect(jsonPath("$.pinCount").value(25))
            .andExpect(jsonPath("$.netCount").value(6)).andExpect(jsonPath("$.issues").isEmpty()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asText();
        mvc.perform(get("/api/designs/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("v1"));
        mvc.perform(get("/api/designs/" + id + "/components")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(12)));
        mvc.perform(get("/api/designs/" + id + "/components/R1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.value").value("1k")).andExpect(jsonPath("$.specification.attributes.Resistance.normalized").value(1000))
            .andExpect(jsonPath("$.specification.attributes.Resistance.unit").value("Ω"))
            .andExpect(jsonPath("$.pins", hasSize(2))).andExpect(jsonPath("$.pins[0].net.id").value("6"))
            .andExpect(jsonPath("$.pins[1].net.id").value("5"));
        mvc.perform(get("/api/designs/" + id + "/components/U1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.value").value("L7805")).andExpect(jsonPath("$.specification.manufacturer").value(nullValue()));
        mvc.perform(get("/api/designs/" + id + "/nets")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(6)));
        mvc.perform(get("/api/designs/" + id + "/nets/6")).andExpect(status().isOk())
            .andExpect(jsonPath("$.components", containsInAnyOrder("C3", "J1", "R1", "U1")));
        mvc.perform(get("/api/designs/" + id + "/issues")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/designs/" + id + "/components/MISSING")).andExpect(status().isNotFound());
        mvc.perform(get("/api/designs/" + id + "/nets/missing")).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/designs/import").file(bom5v()).file(net5v()).param("name", "API 5V").param("version", "v1"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_CONFLICT"));
    }
    @Test
    void realShiftIssuesAreVisibleThroughRest() throws Exception {
        var bom = new MockMultipartFile("bom", "shift.csv", "text/csv", BomParserTest.fixture("shift/Shift_Indicator_PCB_BOM.csv"));
        var net = new MockMultipartFile("netlist", "shift.net", "text/plain", BomParserTest.fixture("shift/Shift_Indicator_PCB.net"));
        String body = mvc.perform(multipart("/api/designs/import").file(bom).file(net).param("name", "API Shift"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.componentCount").value(31)).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asText();
        mvc.perform(get("/api/designs/" + id + "/issues")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code == 'NETLIST_ONLY_COMPONENT')].reference", containsInAnyOrder("H1", "H2", "H3", "H4")));
    }
    @Test
    void malformedInputReturnsParseIssueAndDoesNotReportSuccess() throws Exception {
        var malformed = new MockMultipartFile("netlist", "invalid.net", "text/plain", "(export".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/designs/import").file(bom5v()).file(malformed).param("name", "invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARSE_FAILURE"))
            .andExpect(jsonPath("$.issues[0].source").value("invalid.net"));
    }
    @Test
    void qcvIsExplicitlyUnsupported() throws Exception {
        var qcv = new MockMultipartFile("netlist", "real-required.qcv", "text/plain", new byte[]{1});
        mvc.perform(multipart("/api/designs/import").file(bom5v()).file(qcv).param("name", "unsupported"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FORMAT"));
    }
    @Test
    void validatesRequestsAndReturns404ForUnknownDesign() throws Exception {
        mvc.perform(get("/api/designs/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/designs/invalid-id")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/designs/import").file(bom5v()).file(net5v()).param("name", " "))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/designs/import").file(bom5v()).param("name", "missing-net"))
            .andExpect(status().isBadRequest());
        var empty = new MockMultipartFile("bom", "bom.csv", "text/csv", new byte[0]);
        mvc.perform(multipart("/api/designs/import").file(empty).file(net5v()).param("name", "empty"))
            .andExpect(status().isBadRequest());
    }
}
