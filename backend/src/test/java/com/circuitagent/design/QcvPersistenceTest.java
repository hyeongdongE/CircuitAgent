package com.circuitagent.design;

import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QcvPersistenceTest extends MySqlIntegrationSupport {
    @Autowired CircuitDesignStore store;
    static List<SourceFile> files(String b) throws Exception {
        return List.of(new SourceFile("bom",b+".xlsx",QcvFixtures.file(b,".xlsx")),new SourceFile("netlist",b+".qcv",QcvFixtures.file(b,".qcv")));
    }
    @Test void fullFeModelAndRawFilesRoundTripWithTerminationsAndProvenance() throws Exception {
        var m=QcvIntegrationTest.board("fe"); store.save(m,files("fe")); var loaded=store.load(m.id());
        assertThat(loaded).usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(m);
        assertThat(loaded.components()).hasSize(453); assertThat(loaded.nets()).hasSize(335);
        assertThat(loaded.connections()).hasSize(1500); assertThat(loaded.pins()).hasSize(1643);
        var graph=new CircuitGraph(loaded);
        assertThat(graph.getPinContext("U29","5").status()).isEqualTo(CircuitGraph.ConnectionStatus.TERMINATED);
        assertThat(graph.getComponent("R107").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        assertThat(graph.getPinContext("U16","5").pin().sourceMetadata()).isNotEmpty();
        for(var f:files("fe")) assertThat(store.sourceFiles(m.id()).stream().filter(v->v.role().equals(f.role())).findFirst().orElseThrow().content()).containsExactly(f.content());
    }
    @Test void lateFailureRollsBackWholeLargeImportAndSeparateVersionsRemainIsolated() throws Exception {
        var a=QcvIntegrationTest.board("fe"); var file=files("fe").getFirst();
        assertThatThrownBy(()->store.save(a,List.of(file,file))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->store.load(a.id())).isInstanceOf(NoSuchElementException.class);
        store.save(a,files("fe"));
        var b=new CircuitDesign(UUID.randomUUID(),a.name(),"R3-reimport",a.components(),a.specifications(),a.pins(),a.nets(),a.connections(),a.issues(),a.sourceMetadata());
        store.save(b,files("fe"));
        assertThat(store.load(a.id()).version()).isEqualTo("R3");
        assertThat(store.load(b.id()).version()).isEqualTo("R3-reimport");
        assertThat(new CircuitGraph(store.load(b.id())).getNetOfPin("U16","5").orElseThrow().name()).isEqualTo("U16_EN");
    }
}
