package com.circuitagent.design;

import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QcvIntegrationTest {
    static CircuitDesign board(String board) throws Exception {
        return new CircuitAssembler().assemble("Sparrow-"+board+"-"+UUID.randomUUID(),"R3",
            new BomParser().parse(QcvFixtures.file(board,".xlsx"),board+".xlsx"),
            new QcvNetlistParser().parse(QcvFixtures.file(board,".qcv"),board+".qcv"));
    }
    @Test void feExcelMatchesAllConnectedReferencesAndKeepsPinOnlyPcbParts() throws Exception {
        var bom=new BomParser().parse(QcvFixtures.file("fe",".xlsx"),"BOM.xlsx");
        var net=new QcvNetlistParser().parse(QcvFixtures.file("fe",".qcv"),"Netlist.qcv");
        assertThat(bom.components()).hasSize(445);
        assertThat(bom.components().values().stream().filter(c->c.assemblyStatus()==AssemblyStatus.DNP)).hasSize(51);
        assertThat(net.components().keySet()).containsAll(bom.components().keySet());
        var m=new CircuitAssembler().assemble("FE integration","R3",bom,net); var g=new CircuitGraph(m);
        assertThat(m.components()).hasSize(453); assertThat(m.connections()).hasSize(1500); assertThat(m.nets()).hasSize(335);
        assertThat(m.issues().stream().filter(i->i.code()==ImportIssue.Code.NETLIST_ONLY_COMPONENT)).extracting(ImportIssue::reference)
            .containsExactlyInAnyOrder("PCB1","PCB2","PCB3","PCB4","PCB5","PCB6","PCB7","PCB8");
        assertThat(m.issues()).noneMatch(i->i.code()==ImportIssue.Code.BOM_ONLY_COMPONENT||i.code()==ImportIssue.Code.QUANTITY_MISMATCH);
        assertThat(g.getComponentAttributes("U16").get("Part Number").raw()).isEqualTo("EGP10001429");
        assertThat(g.getComponentSpecification("U16").mpn()).isNull();
        assertThat(g.getComponent("U16").value()).isNull();
        assertThat(g.getComponentAttributes("U16").get("Part Name").raw()).isEqualTo("TPS54302DDC");
        assertThat(g.getNetOfPin("U16","5").orElseThrow().name()).isEqualTo("U16_EN");
        assertThat(g.getComponentAttributes("R107").get("Resistance").normalized()).isEqualByComparingTo("140000");
        assertThat(g.getComponentAttributes("R83").get("Resistance").normalized()).isEqualByComparingTo("56000");
        assertThat(g.getComponent("R107").rawSources().get("bom")).containsEntry("NoMount","NM").containsEntry("Value","140K");
        assertThat(g.getComponent("R107").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        assertThat(g.getComponent("R107").rawSources().get("provenance").get("bomLocation")).startsWith("sheet:BOM:row:");
    }
    @Test void noMountDoesNotPolluteSharedCatalogueSpecification() throws Exception {
        var b=new BomParser().parse(QcvFixtures.file("fe",".xlsx"),"fe.xlsx");
        assertThat(b.components().get("C7").partSpecificationId()).isEqualTo(b.components().get("C8").partSpecificationId());
        assertThat(b.components().get("C7").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        assertThat(b.components().get("C8").assemblyStatus()).isEqualTo(AssemblyStatus.FITTED);
        assertThat(b.specifications().get(b.components().get("C7").partSpecificationId()).attributes()).doesNotContainKeys("NoMount","nomount");
    }
}
