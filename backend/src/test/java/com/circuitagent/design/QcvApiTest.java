package com.circuitagent.design;

import com.circuitagent.MySqlIntegrationSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@AutoConfigureMockMvc
class QcvApiTest extends MySqlIntegrationSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    static MockMultipartFile bom(String board) throws Exception { return new MockMultipartFile("bom","BOM.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",QcvFixtures.file(board,".xlsx")); }
    static MockMultipartFile net(String board) throws Exception { return new MockMultipartFile("netlist","Netlist.qcv","text/plain",QcvFixtures.file(board,".qcv")); }
    @Test void realExcelQcvUploadPersistsAndSupportsStage03Queries() throws Exception {
        String name="FE API "+UUID.randomUUID();
        String body=mvc.perform(multipart("/api/designs/import").file(bom("fe")).file(net("fe")).param("name",name).param("version","R3"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.componentCount").value(453))
            .andExpect(jsonPath("$.netCount").value(335)).andExpect(jsonPath("$.pinCount").value(1643))
            .andExpect(jsonPath("$.connectionCount").value(1500)).andExpect(jsonPath("$.terminatedPinCount").value(135))
            .andReturn().getResponse().getContentAsString();
        String id=json.readTree(body).get("id").asText();
        mvc.perform(get("/api/designs/"+id+"/components/U16")).andExpect(status().isOk())
            .andExpect(jsonPath("$.specification.attributes['Part Number'].raw").value("EGP10001429"))
            .andExpect(jsonPath("$.specification.attributes['Part Name'].raw").value("TPS54302DDC"))
            .andExpect(jsonPath("$.value").value(nullValue()));
        String pin=mvc.perform(get("/api/designs/"+id+"/components/U16/pins/5")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONNECTED")).andExpect(jsonPath("$.net.name").value("U16_EN"))
            .andExpect(jsonPath("$.pin.sourceMetadata.filename").value("Netlist.qcv")).andReturn().getResponse().getContentAsString();
        String netId=json.readTree(pin).get("net").get("id").asText();
        mvc.perform(get("/api/designs/"+id+"/nets/"+netId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.components",containsInAnyOrder("U16","R107","R83"))).andExpect(jsonPath("$.connectedPins",hasSize(3)));
        mvc.perform(get("/api/designs/"+id+"/components/R107")).andExpect(status().isOk())
            .andExpect(jsonPath("$.assemblyStatus").value("DNP")).andExpect(jsonPath("$.specification.attributes.Resistance.normalized").value(140000));
        mvc.perform(get("/api/designs/"+id+"/components/U29/pins/5")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("TERMINATED")).andExpect(jsonPath("$.net").value(nullValue()));
        mvc.perform(get("/api/designs/"+id+"/components/U16/pins")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sourceMetadata.filename").value("Netlist.qcv"));
        mvc.perform(get("/api/designs/"+id+"/issues")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code == 'NETLIST_ONLY_COMPONENT')].reference",hasSize(8)));
        mvc.perform(multipart("/api/designs/import").file(bom("fe")).file(net("fe")).param("name",name).param("version","R3"))
            .andExpect(status().isConflict());
    }
    @Test void malformedQcvRollsBackAndUnknownFormatRemainsExplicitlyUnsupported() throws Exception {
        var bad=new MockMultipartFile("netlist","invalid.qcv","text/plain","NET : 'x' not-a-terminal!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/designs/import").file(bom("fe")).file(bad).param("name","bad-QCV"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARSE_FAILURE"));
        var unknown=new MockMultipartFile("netlist","unknown.net","text/plain",new byte[]{1});
        mvc.perform(multipart("/api/designs/import").file(bom("fe")).file(unknown).param("name","unknown-format").param("netlistFormat","unsupported"))
            .andExpect(status().isBadRequest());
    }
}
