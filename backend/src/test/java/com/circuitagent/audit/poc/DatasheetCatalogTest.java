package com.circuitagent.audit.poc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class DatasheetCatalogTest {
    @Test void officialMappingAndRuleConstantsMatchReviewedCRevisionAndRealCircuit() throws Exception {
        var c=new PocRuleCatalog(new ObjectMapper(),new StructuredRuleAdapter());
        assertThat(c.pinMapping()).containsEntry("EN","5").containsEntry("FB","4").containsEntry("BOOT","6").containsEntry("SW","2");
        var r=c.select(java.util.List.of("tps54302-u16-fb-nominal")).getFirst();
        assertThat(r.source().revision()).isEqualTo("SLVSDG6C / March 2026");assertThat(r.divider().vref()).isEqualByComparingTo("0.596");
        assertThat(r.expected()).isNull();assertThat(r.divider().resistorTolerancePercent()).isNull();
        var g=SnapshotContractTest.real();assertThat(g.getNetOfPin("C5","1")).isEqualTo(g.getNetOfPin("U16","6"));assertThat(g.getNetOfPin("C5","2")).isEqualTo(g.getNetOfPin("U16","2"));
        assertThat(g.getComponentSpecification("U16").mpn()).isNull();
        assertThat(c.select(java.util.List.of("tps54302-u16-vin")).getFirst().expected().max()).isEqualByComparingTo("28");
        assertThatThrownBy(()->c.select(java.util.List.of("invalid"))).isInstanceOf(IllegalArgumentException.class);
    }
}
