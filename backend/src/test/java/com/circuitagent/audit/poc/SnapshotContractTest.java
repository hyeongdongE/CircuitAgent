package com.circuitagent.audit.poc;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.parse.*;
import com.circuitagent.design.model.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class SnapshotContractTest {
    static CircuitGraph real() throws Exception {
        var dir=Path.of(System.getProperty("qcv.fixture.dir"));
        return new CircuitGraph(new CircuitAssembler().assemble("FE integration "+java.util.UUID.randomUUID(),"R3",
            new BomParser().parse(Files.readAllBytes(dir.resolve("fe.xlsx")),"BOM.xlsx"),
            new QcvNetlistParser().parse(Files.readAllBytes(dir.resolve("fe.qcv")),"Netlist.qcv")));
    }
    @Test void realSnapshotExposesVersionPinsAttributesAssemblyIssuesAndSources() throws Exception {
        var g=real();assertThat(g.designSnapshot().version()).isEqualTo("R3");
        var p=g.getPinContext("U16","5");assertThat(p.status()).isEqualTo(CircuitGraph.ConnectionStatus.CONNECTED);
        assertThat(p.pin().name()).isNull();assertThat(p.specification().mpn()).isNull();
        assertThat(p.net().name()).isEqualTo("U16_EN");
        assertThat(g.getComponentsOnNet(p.net().id())).extracting(ComponentInstance::reference).containsExactlyInAnyOrder("U16","R107","R83");
        assertThat(g.getComponent("R107").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        assertThat(g.getComponentAttributes("R107").get("Resistance").normalized()).isEqualByComparingTo("140000");
        assertThat(p.pin().sourceMetadata()).containsKey("filename");assertThat(p.component().rawSources()).containsKeys("bom","provenance");
        assertThat(g.getComponentIssues("U16")).isNotEmpty();
        assertThat(g.getPinContext("U29","5").status()).isEqualTo(CircuitGraph.ConnectionStatus.TERMINATED);
        assertThat(g.getPinContext("U16","unknown").status()).isEqualTo(CircuitGraph.ConnectionStatus.MISSING_PIN);
        assertThatThrownBy(()->g.designSnapshot().components().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
