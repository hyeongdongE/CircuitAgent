package com.circuitagent.design;

import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Generated adversarial inputs supplement, never replace, the unmodified public fixtures. */
class ReviewRegressionTest {
    static BomResult bom(String text) throws Exception { return new BomParser().parse(text.getBytes(StandardCharsets.UTF_8), "review.csv"); }
    static NetlistResult net(String text) throws Exception { return new KiCadNetlistParser().parse(text.getBytes(StandardCharsets.UTF_8), "review.net"); }
    static CircuitDesign model(String bom, String net) throws Exception {
        return new CircuitAssembler().assemble("review-" + UUID.randomUUID(), "v1", bom(bom), net(net));
    }
    @Test void reorderedColumnsGroupedRefsMissingAndZeroRemainDistinct() throws Exception {
        var b = bom("MPN,DNP,Value,References\n,,0R,\"R1,R2\"\n,,,R3\n");
        assertThat(b.components()).hasSize(3);
        assertThat(b.components().get("R3").value()).isNull();
        assertThat(b.specifications().get(b.components().get("R3").partSpecificationId()).attributes()).isEmpty();
        assertThat(b.specifications().get(b.components().get("R1").partSpecificationId()).attributes().get("Resistance").normalized()).isEqualByComparingTo("0");
        assertThat(b.specifications().values()).allMatch(s -> s.mpn() == null);
    }
    @Test void dnpIsPerInstanceAndDoesNotSplitSharedEquivalentMpn() throws Exception {
        var b = bom("Reference,Value,MPN,DNP,Type\nR1,1k,P,Yes,Resistor\nR2,1000Ω,P,No,resistor\nR3,1k,P,,resistor\n");
        assertThat(b.components().get("R1").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        assertThat(b.components().get("R2").assemblyStatus()).isEqualTo(AssemblyStatus.FITTED);
        assertThat(b.components().get("R3").assemblyStatus()).isEqualTo(AssemblyStatus.UNSPECIFIED);
        assertThat(b.specifications()).hasSize(1);
        assertThat(b.specifications().values().iterator().next().attributes()).doesNotContainKeys("DNP", "dnp");
        assertThat(b.components().get("R2").rawSources().get("bom").get("Value")).isEqualTo("1000Ω");
        assertThat(b.issues()).isEmpty();
    }
    @Test void conflictingSpecificationVariantsAreRetainedAndReused() throws Exception {
        var b = bom("Reference,Value,MPN\nR1,1k,P\nR2,2k,P\nR3,2000Ω,P\n");
        assertThat(b.specifications()).hasSize(2);
        assertThat(b.components().get("R2").partSpecificationId()).isEqualTo(b.components().get("R3").partSpecificationId());
        assertThat(b.issues()).extracting(ImportIssue::code).containsExactly(ImportIssue.Code.SPECIFICATION_CONFLICT);
    }
    @Test void unknownDnpAndDuplicateReferencesProduceIssues() throws Exception {
        var b = bom("Reference,Value,DNP\nR1,1k,maybe\nR1,2k,1\n");
        assertThat(b.components().get("R1").value()).isEqualTo("1k");
        assertThat(b.components().get("R1").assemblyStatus()).isEqualTo(AssemblyStatus.UNSPECIFIED);
        assertThat(b.issues()).extracting(ImportIssue::code).contains(ImportIssue.Code.PARSE_FAILURE, ImportIssue.Code.DUPLICATE_REFERENCE);
    }
    @Test void blankHeadersNeverSilentlyDropCsvCells() {
        assertThatThrownBy(() -> bom("Reference,,Value\nR1,secret,1k\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bom("Reference,Value\nR1,1k,extra\n")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void excelRejectsExtraCellsAndMismatchedExtension() throws Exception {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Reference");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("R1"); row.createCell(1).setCellValue("lost");
            workbook.write(bytes);
            assertThatThrownBy(() -> new BomParser().parse(bytes.toByteArray(), "data.xlsx")).isInstanceOf(IllegalArgumentException.class);
        }
        try (var workbook = new HSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            workbook.createSheet().createRow(0).createCell(0).setCellValue("Reference"); workbook.write(bytes);
            assertThatThrownBy(() -> new BomParser().parse(bytes.toByteArray(), "data.xlsx")).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void unitsHandleToleranceAndRejectAbusiveOrIncompatibleValues() {
        assertThat(EngineeringUnits.normalize("±5%", "%").normalized()).isEqualByComparingTo("5");
        assertThat(EngineeringUnits.normalize("4.7uF", "F").normalized()).isEqualByComparingTo("0.0000047");
        assertThat(EngineeringUnits.normalize("2mH", "H").normalized()).isEqualByComparingTo("0.002");
        for (String value : List.of("5k%", "1e9999999999", "1e1000", "-1", "wat"))
            assertThatThrownBy(() -> EngineeringUnits.normalize(value, "%")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sameNetRepeatedDeclarationsMergeAllNodesWithoutMergingOtherNets() throws Exception {
        var n = net("(export (components (comp (ref R1)) (comp (ref R2))) (nets " +
            "(net (code 1) (name GND) (node (ref R1) (pin 1))) " +
            "(net (code 1) (name GND) (node (ref R2) (pin 1)) (node (ref R1) (pin 1))) " +
            "(net (code 2) (name /sheet/GND) (node (ref R1) (pin 2)))))");
        assertThat(n.nets()).hasSize(2); assertThat(n.connections()).hasSize(3);
        assertThat(n.issues()).extracting(ImportIssue::code).containsExactly(ImportIssue.Code.DUPLICATE_NET);
    }
    @Test void conflictingNamesForSameNetAreRejectedRatherThanGuessed() {
        assertThatThrownBy(() -> net("(export (components) (nets (net (code 1) (name GND)) (net (code 1) (name VCC))))"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Conflicting names");
    }
    @Test void aliasPinsHierarchyAndAmbiguousVersusUnconnectedArePreserved() throws Exception {
        var m = model("Reference,Value\nU1,chip\n", """
            (export (components (comp (ref U1) (libsource (lib lib) (part Alias))
              (sheetpath (names /child/) (tstamps /uuid/))))
              (libparts (libpart (lib lib) (part Base) (aliases Alias)
                (pins (pin (num 1) (name FB)) (pin (num 2) (name NC)) (pin (num 3) (name GND)))))
              (nets (net (code 1) (name /child/signal) (node (ref U1) (pin 1)))
                (net (code 2) (name GND) (node (ref U1) (pin 1)) (node (ref U1) (pin 3)))))
            """);
        var g = new CircuitGraph(m);
        assertThat(g.getComponentPins("U1")).hasSize(3);
        assertThat(g.getPinsByName("U1", "FB")).extracting(p -> p.key().number()).containsExactly("1");
        assertThat(g.getPinContext("U1", "1").status()).isEqualTo(CircuitGraph.ConnectionStatus.AMBIGUOUS);
        assertThat(g.getPinContext("U1", "2").status()).isEqualTo(CircuitGraph.ConnectionStatus.UNCONNECTED);
        assertThat(g.getPinContext("U1", "3").net().name()).isEqualTo("GND");
        assertThat(g.getPinContext("U1", "99").status()).isEqualTo(CircuitGraph.ConnectionStatus.MISSING_PIN);
        assertThat(g.getComponent("U1").rawSources().get("netlist")).containsEntry("sheetpath:tstamps", "/uuid/");
        assertThat(m.nets().get("1").name()).isEqualTo("/child/signal");
    }
    @Test void publicCircuitSupportsExplicitComponentCrossingWithoutNetUnion() throws Exception {
        var g = new CircuitGraph(CircuitGraphTest.real5v()); var start = new PinKey("R1", "1");
        var limited = g.explore(start, Set.of(), 3);
        assertThat(limited.nets()).containsExactly("6");
        assertThat(limited.pins()).doesNotContain(new PinKey("R1", "2"));
        var crossing = g.explore(start, Set.of("R1"), 3);
        assertThat(crossing.nets()).containsExactlyInAnyOrder("6", "5");
        assertThat(crossing.pins()).contains(new PinKey("D5", "2"));
        assertThat(g.getNetOfPin("R1", "1").orElseThrow().id()).isEqualTo("6");
        assertThat(g.getNetOfPin("R1", "2").orElseThrow().id()).isEqualTo("5");
        assertThatThrownBy(() -> g.explore(start, Set.of("R1"), 33)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void publicCircuitPreservesMultipleTopologyRoutesAndCycles() throws Exception {
        var g = new CircuitGraph(CircuitGraphTest.real5v());
        var n = g.explore(new PinKey("U1", "3"), Set.of("C3", "R1", "D5"), 6);
        assertThat(n.edges()).anyMatch(e -> e.kind() == CircuitGraph.EdgeKind.COMPONENT && e.from().reference().equals("C3"));
        assertThat(n.edges()).anyMatch(e -> e.kind() == CircuitGraph.EdgeKind.COMPONENT && e.from().reference().equals("R1"));
        assertThat(n.edges()).anyMatch(e -> e.kind() == CircuitGraph.EdgeKind.COMPONENT && e.from().reference().equals("D5"));
        assertThat(n.nets()).contains("1", "5", "6");
        assertThat(n.pins()).doesNotHaveDuplicates();
    }
    @Test void dnpPlacementSurvivesAssemblyAndStopsComponentCrossing() throws Exception {
        var b = bom("Reference,Value,DNP\nR1,1k,yes\n");
        var n = net("(export (components (comp (ref R1) (value 1k))) (nets (net (code 1) (name A) (node (ref R1) (pin 1))) (net (code 2) (name B) (node (ref R1) (pin 2)))))");
        var g = new CircuitGraph(new CircuitAssembler().assemble("DNP", "v1", b, n));
        assertThat(g.getComponent("R1").assemblyStatus()).isEqualTo(AssemblyStatus.DNP);
        assertThat(g.explore(new PinKey("R1", "1"), Set.of("R1"), 4).nets()).containsExactly("1");
    }
    @Test void malformedAndExcessiveInputDoesNotReturnPartialModel() {
        assertThatThrownBy(() -> net("(export (components) (nets)) trailing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KiCadNetlistParser().parse(new byte[ParserLimits.MAX_BYTES + 1], "large.net"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SExpression.parse("(" + "x ".repeat(ParserLimits.MAX_VALUES) + ")"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expression count");
    }
    @Test void compressedExcelExpansionIsRejectedBeforeWorkbookAllocation() throws Exception {
        try (var bytes = new ByteArrayOutputStream()) {
            try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
                byte[] block = new byte[8192];
                for (int i = 0; i <= ParserLimits.MAX_EXPANDED_BYTES / block.length; i++) zip.write(block);
                zip.closeEntry();
            }
            assertThat(bytes.size()).isLessThan(ParserLimits.MAX_BYTES);
            assertThatThrownBy(() -> new BomParser().parse(bytes.toByteArray(), "bomb.xlsx"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expanded content limit");
        }
    }
    @Test void explicitPhysicalAttributeConflictIsReportedWithoutInventingValues() throws Exception {
        var b = bom("Reference,Value,Resistance\nR1,1k,2kΩ\n");
        var c = b.components().get("R1");
        assertThat(c.value()).isEqualTo("1k");
        assertThat(b.specifications().get(c.partSpecificationId()).attributes().get("Resistance").normalized()).isEqualByComparingTo("2000");
        assertThat(b.issues()).extracting(ImportIssue::code).containsExactly(ImportIssue.Code.VALUE_MISMATCH);
        assertThat(c.rawSources().get("provenance")).containsEntry("bomLocation", "csv:record:2");
    }
    @Test void unknownPinInventoryIsNotReportedAsConfirmedUnconnected() throws Exception {
        var base = model("Reference,Value\nU1,chip\n", "(export (components (comp (ref U1))) (nets))");
        var design = new CircuitDesign(base.id(), base.name(), base.version(), base.components(), base.specifications(),
            List.of(new ComponentPin(new PinKey("U1", "1"), "FB", null)), base.nets(), base.connections(), base.issues(), base.sourceMetadata());
        assertThat(new CircuitGraph(design).getPinContext("U1", "1").status()).isEqualTo(CircuitGraph.ConnectionStatus.UNRESOLVED);
    }

}
