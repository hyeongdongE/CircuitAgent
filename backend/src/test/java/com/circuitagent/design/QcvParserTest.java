package com.circuitagent.design;

// QcvParserTest: 실제 공개 Fixture의 회로 사실과 파싱 결과를 검증한다.

import static org.assertj.core.api.Assertions.*;

import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class QcvParserTest {
  static NetlistResult parse(String data) throws Exception {
    return new QcvNetlistParser().parse(data.getBytes(StandardCharsets.UTF_8), "adversarial.qcv");
  }

  @Test
  void realFeConnectionsAndSeparatePinsMatchSourceFacts() throws Exception {
    var n = new QcvNetlistParser().parse(QcvFixtures.file("fe", ".qcv"), "fe.qcv");
    assertThat(n.nets()).hasSize(335);
    assertThat(n.connections()).hasSize(1500);
    assertThat(n.pins()).hasSize(1643);
    assertThat(n.components()).hasSize(453);
    assertThat(n.sourceMetadata())
        .containsEntry("separatePinRecords", "143")
        .containsEntry("terminatedPinRecords", "135");
    assertThat(n.pins().stream().filter(p -> p.disposition() == PinDisposition.TERMINATED))
        .hasSize(135);
    assertThat(n.pins().stream().filter(p -> p.disposition() == PinDisposition.UNCONNECTED))
        .hasSize(8);
    assertThat(n.issues()).isEmpty();
    for (String name : List.of("U16_EN", "U16_FB", "VDD", "GND")) {
      var net =
          n.nets().values().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
      String line =
          new String(QcvFixtures.file("fe", ".qcv"), StandardCharsets.UTF_8)
              .lines()
              .filter(l -> l.startsWith("NET : '" + name + "'"))
              .findFirst()
              .orElseThrow();
      var expected =
          Arrays.stream(line.substring(line.lastIndexOf('\'') + 1).trim().split("\\s+"))
              .map(
                  t ->
                      new PinKey(
                          t.substring(0, t.lastIndexOf('-')), t.substring(t.lastIndexOf('-') + 1)))
              .toList();
      assertThat(
              n.connections().stream()
                  .filter(c -> c.netId().equals(net.id()))
                  .map(PinConnection::pin))
          .containsExactlyInAnyOrderElementsOf(expected);
    }
    var en =
        n.nets().values().stream().filter(v -> v.name().equals("U16_EN")).findFirst().orElseThrow();
    assertThat(
            n.connections().stream().filter(c -> c.netId().equals(en.id())).map(PinConnection::pin))
        .containsExactlyInAnyOrder(
            new PinKey("R107", "2"), new PinKey("R83", "2"), new PinKey("U16", "5"));
    assertThat(
            n.pins().stream()
                .filter(p -> p.key().equals(new PinKey("U29", "5")))
                .findFirst()
                .orElseThrow()
                .sourceMetadata())
        .containsKey("filename");
  }

  @Test
  void repeatedNetAndDuplicateConnectionAreDeduplicatedAndFlagged() throws Exception {
    var n = parse("NET : '/child/rail+~' U-1-A1 R1-2\nNET : '/child/rail+~' R1-2 R2-1\n");
    assertThat(n.nets()).hasSize(1);
    assertThat(n.connections()).hasSize(3);
    assertThat(n.components()).containsKey("U-1");
    assertThat(n.issues())
        .extracting(ImportIssue::code)
        .contains(ImportIssue.Code.DUPLICATE_NET, ImportIssue.Code.DUPLICATE_CONNECTION);
  }

  @Test
  void termIsNotAnErrorAndContradictoryConnectionsAreOmitted() throws Exception {
    var n =
        parse(
            "# begin un-connected pins list\nPIN : '/U1' U1-5 (by TERM)\nPIN : '/PCB1' PCB1-1\nNET : 'A' R1-1\nNET : 'B' R1-1\n");
    assertThat(n.connections()).isEmpty();
    assertThat(n.pins())
        .anyMatch(
            p ->
                p.key().equals(new PinKey("U1", "5"))
                    && p.disposition() == PinDisposition.TERMINATED);
    assertThat(n.issues())
        .extracting(ImportIssue::code)
        .containsExactly(ImportIssue.Code.AMBIGUOUS_CONNECTION);
  }

  @Test
  void malformedUnknownEmptyAndBadEncodingAreRejected() {
    for (String s :
        List.of(
            "",
            "# comments only",
            "COMP: U1",
            "NET : 'bad U1-1",
            "NET : 'x' -1",
            "PIN : '/U1' U1-1 (by OTHER)",
            "NET : 'x' U1-1\n R2-1"))
      assertThatThrownBy(() -> parse(s)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QcvNetlistParser().parse(new byte[] {(byte) 0xff}, "bad.qcv"))
        .isInstanceOf(java.nio.charset.CharacterCodingException.class);
  }
}
