package com.circuitagent.design;

import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import com.circuitagent.design.tools.CsvToXlsx;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class QcvRobustnessTest {
    @Test void allSixOriginalSourceChecksumsRemainPinned() throws Exception {
        var root=new ObjectMapper().readTree(QcvFixtures.dir().getParent().getParent().resolve("scripts/qcv-fixtures.json").toFile());
        assertThat(root.get("files").size()).isEqualTo(6);
        for(var f:root.get("files")) {
            byte[] bytes=Files.readAllBytes(QcvFixtures.dir().resolve(f.get("local").asText()));
            assertThat(bytes.length).isEqualTo(f.get("bytes").asInt());
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(f.get("sha256").asText());
        }
    }
    @Test void observedEmbeddedAndDecimalCommaUnitsAreNormalizedWithoutLosingRaw() throws Exception {
        assertThat(EngineeringUnits.normalize("2n2 ","F").normalized()).isEqualByComparingTo("0.0000000022");
        assertThat(EngineeringUnits.normalize("3V6","V").normalized()).isEqualByComparingTo("3.6");
        assertThat(EngineeringUnits.normalize("2,5","V").normalized()).isEqualByComparingTo("2.5");
        assertThatThrownBy(()->EngineeringUnits.normalize("1,000","V")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->EngineeringUnits.normalize("3V6","Ω")).isInstanceOf(IllegalArgumentException.class);
        var g=new CircuitGraph(QcvIntegrationTest.board("fe"));
        assertThat(g.getComponentAttributes("C12").get("Capacitance").raw()).isEqualTo("2n2 ");
        assertThat(g.getComponentAttributes("C12").get("Capacitance").normalized()).isEqualByComparingTo("0.0000000022");
        assertThat(g.getComponentAttributes("U27").get("Voltage").normalized()).isEqualByComparingTo("2.5");
        assertThat(g.getComponentAttributes("U27")).doesNotContainKey("Voltage Rating");
        assertThat(g.getComponentIssues("U27")).noneMatch(i->i.code()==ImportIssue.Code.INVALID_UNIT);
    }
    @Test void catalogueDuplicateQuantityMissingValuesAndNoMountIssuesAreExplicit() throws Exception {
        byte[] xlsx=CsvToXlsx.convert("PartNumber;Rev;RefDes;QtyOnBoard;NoMount;Value;Voltage;Part Name\n;0;R1,R2;3;NM;0R;;zero resistor\n;0;R1;1;;;;\n;0;R3;1;unknown;;;\n".getBytes(StandardCharsets.UTF_8));
        var b=new BomParser().parse(xlsx,"adversarial.xlsx");
        assertThat(b.components()).hasSize(3);
        assertThat(b.issues()).extracting(ImportIssue::code).contains(ImportIssue.Code.QUANTITY_MISMATCH,ImportIssue.Code.DUPLICATE_REFERENCE,ImportIssue.Code.MISSING_VALUE,ImportIssue.Code.PARSE_FAILURE);
        assertThat(b.components().get("R1").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        assertThat(b.specifications().get(b.components().get("R1").partSpecificationId()).mpn()).isNull();
        assertThat(b.specifications().get(b.components().get("R1").partSpecificationId()).attributes().get("Resistance").normalized()).isEqualByComparingTo("0");
        assertThat(b.components().get("R3").assemblyStatus()).isEqualTo(AssemblyStatus.UNSPECIFIED);
    }
    @Test void unicodeHierarchicalNetNamesAndHyphenatedReferencesRemainLiteral() throws Exception {
        var n=QcvParserTest.parse("NET : '/层次/rail (A) ~+' /sheet-alpha/U-device-A1 R1-1\n");
        assertThat(n.nets().values()).extracting(CircuitNet::name).containsExactly("/层次/rail (A) ~+");
        assertThat(n.pins()).extracting(ComponentPin::key).contains(new PinKey("/sheet-alpha/U-device","A1"));
    }
    @Test void contradictoryTermAndNetNeverCreateAConnection() throws Exception {
        var n=QcvParserTest.parse("PIN : '/U1' U1-5 (by TERM)\nNET : 'rail' U1-5\n");
        assertThat(n.connections()).isEmpty();
        assertThat(n.issues()).extracting(ImportIssue::code).contains(ImportIssue.Code.AMBIGUOUS_CONNECTION);
        assertThat(n.pins().getFirst().sourceMetadata()).containsKeys("record:1","record:2");
    }
    @Test void repeatedPinIsDeduplicatedWithBothSourceLocations() throws Exception {
        var n=QcvParserTest.parse("PIN : '/U1' U1-5 (by TERM)\nPIN : '/U1' U1-5 (by TERM)\n");
        assertThat(n.pins()).hasSize(1);assertThat(n.nets()).isEmpty();
        assertThat(n.sourceMetadata()).containsEntry("separatePinRecords","2");
        assertThat(n.issues()).extracting(ImportIssue::code).containsExactly(ImportIssue.Code.DUPLICATE_CONNECTION);
    }
    @Test void bomOnlyAndQcvOnlyAreRetainedAndNoValuesAreMatchedByGuess() throws Exception {
        var b=ReviewRegressionTest.bom("Reference,Value\nR1,1k\nR2,2k\n");
        var n=QcvParserTest.parse("NET : 'x' R1-1 U99-1\n");
        var m=new CircuitAssembler().assemble("mismatch","R3",b,n);
        assertThat(m.components()).containsKeys("R1","R2","U99");
        assertThat(m.issues()).extracting(ImportIssue::code).contains(ImportIssue.Code.BOM_ONLY_COMPONENT,ImportIssue.Code.NETLIST_ONLY_COMPONENT);
        assertThat(m.components().get("U99").value()).isNull();
    }
    @Test void mixedRealBoardsProduceMismatchIssuesRatherThanDroppingReferences() throws Exception {
        var b=new BomParser().parse(QcvFixtures.file("fe",".xlsx"),"FE-R3.xlsx");
        var n=new QcvNetlistParser().parse(QcvFixtures.file("ntop",".qcv"),"NTop-R3.qcv");
        var m=new CircuitAssembler().assemble("mixed","user-version",b,n);
        assertThat(m.components().keySet()).containsAll(b.components().keySet()).containsAll(n.components().keySet());
        assertThat(m.issues()).extracting(ImportIssue::code).contains(ImportIssue.Code.BOM_ONLY_COMPONENT,ImportIssue.Code.NETLIST_ONLY_COMPONENT);
        assertThat(m.version()).isEqualTo("user-version"); // no board revision inferred from per-part Rev
    }
    @Test void declaredOnePinSectionRemainsUnresolvedInsteadOfFabricatingNet() throws Exception {
        var n=QcvParserTest.parse("# begin one pin nets list\nPIN : 'label' U1-1\n");
        assertThat(n.nets()).isEmpty();assertThat(n.connections()).isEmpty();
        assertThat(n.pins().getFirst().disposition()).isEqualTo(PinDisposition.UNSPECIFIED);
    }
    @Test void largeGeneratedInputAndTraversalStayBounded() throws Exception {
        var input=new StringBuilder("NET : 'stress' ");
        for(int i=1;i<=5000;i++)input.append("R").append(i).append("-1 R").append(i).append("-2 ");
        var n=QcvParserTest.parse(input.toString());assertThat(n.connections()).hasSize(10000);
        var m=new CircuitDesign(UUID.randomUUID(),"stress","v1",n.components(),n.specifications(),n.pins(),n.nets(),n.connections(),n.issues(),n.sourceMetadata());
        assertThatThrownBy(()->new CircuitGraph(m).explore(new PinKey("R1","1"),Set.of(),2)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget");
        assertThatThrownBy(()->new QcvNetlistParser().parse(new byte[ParserLimits.MAX_BYTES+1],"huge.qcv")).isInstanceOf(IllegalArgumentException.class);
    }
}
