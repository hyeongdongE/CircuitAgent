package com.circuitagent.design.parse;

import static com.circuitagent.design.model.ImportIssue.*;

import com.circuitagent.design.model.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 공개 Sparrow PADS QCV에서 관측한 NET/PIN 행 문법의 독립 Java 어댑터. NET은 전기 연결, PIN은 별도 핀 선언이며 TERM은 의도적 종단일 수
 * 있다. COMP 목록 없이 관측된 단자로 부품을 구성한다. 삼성의 모든 QCV 변형을 지원한다는 의미는 아니다.
 */
@Component
public class QcvNetlistParser implements NetlistParser {
  // Reference는 마지막 유효 단자 접미부 앞까지 잡는다. 계층 경로와 Reference 내부 하이픈을 보존한다.
  private static final Pattern TERMINAL =
      Pattern.compile("([/A-Za-z_][A-Za-z0-9_./()+:-]*)-([A-Za-z0-9_]+)");
  private static final Pattern RECORD = Pattern.compile("(NET|PIN)\\s*:\\s*'([^']+)'\\s+(.+)");

  @Override
  public String format() {
    return "qcv";
  }

  @Override
  public NetlistResult parse(byte[] bytes, String filename) throws IOException {
    ParserLimits.file(bytes);
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    Map<String, ComponentInstance> components = new LinkedHashMap<>();
    Map<UUID, PartSpecification> specs = new LinkedHashMap<>();
    Map<PinKey, ComponentPin> pins = new LinkedHashMap<>();
    Map<String, CircuitNet> nets = new LinkedHashMap<>();
    Map<PinKey, PinConnection> connections = new LinkedHashMap<>();
    // 한 번 충돌한 핀은 이후 다른 선언이 나와도 정상 연결로 복구하지 않는다.
    Set<PinKey> ambiguous = new HashSet<>();
    List<ImportIssue> issues = new ArrayList<>();
    int number = 0, separate = 0, terminated = 0, tokenCount = 0;
    String section = "unspecified";
    // 행별 원문 위치를 추적한다. # section은 PIN 의미를 해석하는 공개 exporter의 문맥이다.
    for (String original : text.lines().toList()) {
      int line = ++number;
      String value = original.trim();
      if (value.isBlank()) continue;
      if (value.startsWith("#")) {
        section = value.substring(1).trim();
        continue;
      }
      if (line > ParserLimits.MAX_ROWS || value.length() > 100_000)
        throw syntax(filename, line, "QCV line budget exceeded");
      var record = RECORD.matcher(value);
      if (!record.matches()) throw syntax(filename, line, "Expected quoted NET or PIN record");
      String kind = record.group(1), label = record.group(2), tail = record.group(3).trim();
      // PIN의 (by TERM)은 종단 선언이다. NET 연결을 생성하거나 오류로 간주하지 않는다.
      boolean term = kind.equals("PIN") && tail.endsWith("(by TERM)");
      if (term) tail = tail.substring(0, tail.length() - "(by TERM)".length()).trim();
      String[] identifiers = tail.split("\\s+");
      if (tail.isBlank() || kind.equals("PIN") && identifiers.length != 1)
        throw syntax(filename, line, "PIN requires one terminal and only optional (by TERM)");
      CircuitNet net = null;
      // 같은 이름의 NET 반복은 관측된 공개 규칙에 따라 합치고 중복 선언 경고를 남긴다.
      if (kind.equals("NET")) {
        String id = id(label);
        net = new CircuitNet(id, label, filename + ":line:" + line);
        if (nets.putIfAbsent(id, net) != null)
          issues.add(
              new ImportIssue(
                  Severity.WARNING,
                  Code.DUPLICATE_NET,
                  null,
                  filename + ":line:" + line,
                  "Repeated NET name; terminal sets merged"));
      } else {
        separate++;
        if (term) terminated++;
      }
      for (String token : identifiers) {
        if (++tokenCount > ParserLimits.MAX_VALUES)
          throw syntax(filename, line, "QCV terminal budget exceeded");
        var terminal = TERMINAL.matcher(token);
        if (!terminal.matches()) throw syntax(filename, line, "Invalid terminal identifier");
        String ref = terminal.group(1), pinNumber = terminal.group(2);
        if (ref.length() > 191 || pinNumber.length() > 128)
          throw syntax(filename, line, "Terminal identifier exceeds database limits");
        // QCV에 COMP 목록이 없어도 단자를 관측한 Reference는 부품으로 유지한다. 사양은 BOM이 제공한다.
        if (!components.containsKey(ref)) {
          var spec = new PartSpecification(UUID.randomUUID(), null, null, null, Map.of());
          specs.put(spec.id(), spec);
          components.put(
              ref,
              new ComponentInstance(
                  ref,
                  null,
                  spec.id(),
                  Map.of("netlist", Map.of("format", "qcv", "pinInventory", "observed-only")),
                  filename + ":line:" + line));
        }
        var key = new PinKey(ref, pinNumber);
        var previousPin = pins.get(key);
        var source =
            new LinkedHashMap<String, String>(
                previousPin == null ? Map.of() : previousPin.sourceMetadata());
        source.put("filename", filename);
        // 동일 핀이 여러 행에 등장해도 이전 출처를 잃지 않도록 행 번호별 원문을 누적한다.
        source.put(
            "record:" + line, kind + ": '" + label + "' " + token + (term ? " (by TERM)" : ""));
        source.put("section:" + line, section);
        // one pin nets 목록은 완전한 미연결 선언으로 단정하지 않는다. 명시 TERM 및 다른 PIN 선언과 구분한다.
        PinDisposition disposition =
            kind.equals("NET")
                ? PinDisposition.UNSPECIFIED
                : term
                    ? PinDisposition.TERMINATED
                    : section.equals("begin one pin nets list")
                        ? PinDisposition.UNSPECIFIED
                        : PinDisposition.UNCONNECTED;
        if (previousPin != null) {
          boolean declaredBefore = previousPin.disposition() != PinDisposition.UNSPECIFIED;
          if (kind.equals("PIN"))
            issues.add(
                new ImportIssue(
                    Severity.WARNING,
                    Code.DUPLICATE_CONNECTION,
                    ref,
                    filename + ":line:" + line,
                    "Repeated terminal declaration; one pin retained"));
          if (declaredBefore && kind.equals("NET")
              || kind.equals("PIN") && connections.containsKey(key)
              || declaredBefore && kind.equals("PIN") && previousPin.disposition() != disposition) {
            ambiguous.add(key);
            connections.remove(key);
            issues.add(ambiguity(ref, pinNumber, filename, line));
          }
          // 명시적 핀 선언을 NET의 미확정 metadata로 덮어쓰지 않는다.
          if (declaredBefore) disposition = previousPin.disposition();
        }
        pins.put(key, new ComponentPin(key, null, null, disposition, source));
        // 같은 Net의 반복 핀은 한 연결만 유지한다. 서로 다른 Net 배정은 제외하고 AMBIGUOUS로 전달한다.
        if (net != null && !ambiguous.contains(key)) {
          var previous = connections.putIfAbsent(key, new PinConnection(key, net.id()));
          if (previous != null) {
            if (!previous.netId().equals(net.id())) {
              ambiguous.add(key);
              connections.remove(key);
              issues.add(ambiguity(ref, pinNumber, filename, line));
            } else
              issues.add(
                  new ImportIssue(
                      Severity.WARNING,
                      Code.DUPLICATE_CONNECTION,
                      ref,
                      filename + ":line:" + line,
                      "Repeated terminal on same Net; connection deduplicated"));
          }
        }
      }
    }
    if (pins.isEmpty()) throw syntax(filename, number, "QCV contains no terminals");
    return new NetlistResult(
        components,
        specs,
        List.copyOf(pins.values()),
        nets,
        List.copyOf(connections.values()),
        issues,
        Map.of(
            "format",
            "qcv",
            "dialect",
            "public-pads-net-pin",
            "encoding",
            "UTF-8",
            "filename",
            filename,
            "separatePinRecords",
            Integer.toString(separate),
            "terminatedPinRecords",
            Integer.toString(terminated)));
  }

  private static ImportIssue ambiguity(String ref, String number, String filename, int line) {
    return new ImportIssue(
        Severity.ERROR,
        Code.AMBIGUOUS_CONNECTION,
        ref,
        "pin:" + number,
        "Pin "
            + number
            + " assigned to conflicting declarations; connection omitted at "
            + filename
            + ":line:"
            + line);
  }

  private static IllegalArgumentException syntax(String filename, int line, String message) {
    return new IllegalArgumentException(message + " at " + filename + ":line:" + line);
  }

  // 긴/특수문자 Net 이름 대신 SHA 기반 안정 ID를 사용한다. 원문 이름은 CircuitNet.name에 그대로 남긴다.
  private static String id(String label) {
    try {
      return "qcv:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(label.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
