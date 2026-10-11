package com.circuitagent.design;

import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NetlistParserTest {
    private final KiCadNetlistParser parser = new KiCadNetlistParser();
    @Test
    void real5vNetlistHas12Components25PinsAnd6Nets() throws Exception {
        var result = parser.parse(BomParserTest.fixture("5v/5Volt_Power_Supply.net"), "5v.net");
        assertThat(result.components()).hasSize(12);
        assertThat(result.nets()).hasSize(6);
        assertThat(result.pins()).hasSize(25);
        assertThat(result.connections()).hasSize(25);
        assertThat(result.issues()).isEmpty();
        assertThat(result.components().get("U1").value()).isEqualTo("L7805");
        assertThat(result.pins().stream().filter(p -> p.key().reference().equals("R1"))).hasSize(2);
        assertThat(result.connections()).contains(new PinConnection(new PinKey("R1", "1"), "6"), new PinConnection(new PinKey("R1", "2"), "5"));
        assertThat(result.connections().stream().filter(c -> c.netId().equals("1"))).hasSize(8);
        assertThat(result.pins()).contains(new ComponentPin(new PinKey("U1", "1"), "IN", "power_in"));
        assertThat(result.sourceMetadata().get("source")).endsWith("5Volt_Power_Supply.kicad_sch");
        // Common model also validates every relationship produced by the parser.
        new CircuitDesign(UUID.randomUUID(), "5v", "v1", result.components(), result.specifications(), result.pins(), result.nets(), result.connections(), result.issues(), result.sourceMetadata());
    }
    @Test
    void realShiftNetlistIncludesMountingHolesAndPreservesHierarchicalNames() throws Exception {
        var result = parser.parse(BomParserTest.fixture("shift/Shift_Indicator_PCB.net"), "shift.net");
        assertThat(result.components()).hasSize(31).containsKeys("H1", "H2", "H3", "H4", "SN65HVDA195");
        assertThat(result.nets()).isNotEmpty();
        assertThat(result.pins()).isNotEmpty();
        assertThat(result.issues()).isEmpty();
        new CircuitDesign(UUID.randomUUID(), "shift", "v1", result.components(), result.specifications(), result.pins(), result.nets(), result.connections(), result.issues(), result.sourceMetadata());
    }
    @Test
    void tokenizerHandlesStringsEscapesNestedListsAndReportsMalformedSyntax() {
        var parsed = SExpression.parse("(export (value \"Net-(x) \\\"quoted\\\" \\\\ path\") (nested (child atom)))");
        assertThat(parsed.field("value")).isEqualTo("Net-(x) \"quoted\" \\ path");
        assertThat(parsed.child("nested").orElseThrow().field("child")).isEqualTo("atom");
        for (String malformed : List.of("(export", "(export \"unterminated)", "(export))", "(export)(extra)", "(".repeat(260))) {
            assertThatThrownBy(() -> SExpression.parse(malformed)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void ambiguousConnectionsAreOmittedAndLibraryUnconnectedPinsRemain() throws Exception {
        String data = """
            (export (components (comp (ref U1) (value CHIP) (libsource (lib L) (part P))))
              (libparts (libpart (lib L) (part P) (pins (pin (num 1) (name IN) (type input)) (pin (num 2) (name NC) (type passive)))))
              (nets (net (code 1) (name /power/VIN) (node (ref U1) (pin 1)))
                    (net (code 2) (name GND) (node (ref U1) (pin 1)) (node (ref MISSING) (pin 1)))))
            """;
        var result = parser.parse(data.getBytes(StandardCharsets.UTF_8), "supplementary.net");
        assertThat(result.pins()).hasSize(2);
        assertThat(result.connections()).isEmpty();
        assertThat(result.nets().get("1").name()).isEqualTo("/power/VIN");
        assertThat(result.issues()).extracting(ImportIssue::code).containsExactly(ImportIssue.Code.AMBIGUOUS_CONNECTION, ImportIssue.Code.UNKNOWN_COMPONENT);
    }
    @Test
    void qcvRejectsEmptyInputRatherThanPretendingToParseIt() {
        assertThatThrownBy(() -> new QcvNetlistParser().parse(new byte[0], "design.qcv"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no terminals");
    }
}
