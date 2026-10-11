package com.circuitagent.design.parse;

import static com.circuitagent.design.model.ImportIssue.*;

import com.circuitagent.design.model.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

/**
 * CSV/XLSX/XLS의 한 행을 개별 부품으로 펼치는 BOM 어댑터. 컬럼 별칭을 통일하고 숫자를 SI 단위로 정규화하되 원문과 행 위치는 별도로 보존한다.
 * 누락·중복·충돌은 ImportIssue로 전달하며, 없는 값을 0이나 제조사 MPN으로 생성하지 않는다.
 */
@Component
public class BomParser {
  private record Row(String location, Map<String, String> raw) {}

  // 컬럼 순서가 달라도 이름으로 식별한다. 알 수 없는 컬럼은 동적 속성으로 남긴다.
  private static String canonical(String header) {
    String key = header.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return switch (key) {
      case "reference", "references", "ref", "refs", "designator", "designators", "refdes" ->
          "reference";
      case "value", "designation" -> "value";
      case "qty", "quantity", "qtyonboard" -> "quantity";
      case "mpn", "partnumber", "manufacturerpartnumber" -> "mpn";
      case "manufacturer", "mfr" -> "manufacturer";
      case "type", "componenttype" -> "type";
      case "voltagerating", "voltage" -> "Voltage Rating";
      case "currentrating", "current" -> "Current Rating";
      case "resistance" -> "Resistance";
      case "capacitance" -> "Capacitance";
      case "inductance" -> "Inductance";
      case "tolerance" -> "Tolerance";
      case "nomount" -> "nomount";
      case "dnp", "dnf", "donotpopulate", "donotfit" -> "dnp";
      default -> header.trim();
    };
  }

  public BomResult parse(byte[] bytes, String filename) throws IOException {
    ParserLimits.file(bytes);
    String lower = filename.toLowerCase(Locale.ROOT);
    List<Row> rows;
    if (lower.endsWith(".csv")) rows = csvRows(bytes);
    else if (lower.endsWith(".xlsx") || lower.endsWith(".xls"))
      rows = excelRows(bytes, lower.endsWith(".xlsx"));
    else throw new IllegalArgumentException("Supported BOM formats: CSV, XLSX, XLS");
    Map<String, ComponentInstance> components = new LinkedHashMap<>();
    Map<UUID, PartSpecification> specs = new LinkedHashMap<>();
    Map<List<String>, List<PartSpecification>> byMpn = new HashMap<>();
    List<ImportIssue> issues = new ArrayList<>();
    // 행별 처리를 시작한다. 먼저 컬럼 의미를 통일하고 실제 Reference만 분리한다.
    for (var row : rows) {
      Map<String, String> fields = new LinkedHashMap<>();
      boolean catalogue = catalogueHeaders(row.raw().keySet());
      row.raw().forEach((k, v) -> fields.put(column(k, catalogue), v));
      String references = clean(fields.get("reference"));
      if (references == null) {
        issues.add(
            new ImportIssue(
                Severity.ERROR, Code.MISSING_REFERENCE, null, row.location(), "Missing Reference"));
        continue;
      }
      // Reference 범위를 추측해서 확장하지 않는다. 명시된 Reference만 유지한다.
      // R1,R2 또는 공백 구분은 펼치지만 R1-R3 범위는 추측하여 확장하지 않는다.
      var refs = Arrays.stream(references.split("[,;\\s]+")).filter(s -> !s.isBlank()).toList();
      String quantity = clean(fields.get("quantity"));
      if (quantity != null) {
        try {
          if (new java.math.BigDecimal(quantity).intValueExact() != refs.size())
            issues.add(
                new ImportIssue(
                    Severity.WARNING,
                    Code.QUANTITY_MISMATCH,
                    references,
                    row.location(),
                    "Quantity differs from explicit Reference count"));
        } catch (ArithmeticException | NumberFormatException e) {
          issues.add(
              new ImportIssue(
                  Severity.ERROR,
                  Code.PARSE_FAILURE,
                  references,
                  row.location(),
                  "Invalid quantity: " + quantity));
        }
      }
      // 같은 BOM 행의 부품들은 사양을 공유할 수 있어도 ComponentInstance는 각각 생성한다.
      for (String ref : refs) {
        if (components.containsKey(ref)) {
          issues.add(
              new ImportIssue(
                  Severity.ERROR,
                  Code.DUPLICATE_REFERENCE,
                  ref,
                  row.location(),
                  "Duplicate Reference; first instance retained"));
          continue;
        }
        String value = clean(fields.get("value"));
        if (value == null)
          issues.add(
              new ImportIssue(
                  Severity.WARNING,
                  Code.MISSING_VALUE,
                  ref,
                  row.location(),
                  "Missing component value"));
        String type = clean(fields.get("type"));
        if (type == null) type = inferType(ref);
        else type = type.toLowerCase(Locale.ROOT);
        // 실장 상태는 부품 사양과 별개다. Sparrow NoMount 빈 칸과 generic DNP 빈 칸은 의미가 다르다.
        AssemblyStatus placement =
            fields.containsKey("nomount")
                ? noMount(fields.get("nomount"), ref, row.location(), issues)
                : assemblyStatus(fields.get("dnp"), ref, row.location(), issues);
        if (fields.containsKey("nomount")
            && clean(fields.get("dnp")) != null
            && placement != assemblyStatus(fields.get("dnp"), ref, row.location(), issues)) {
          issues.add(
              new ImportIssue(
                  Severity.ERROR,
                  Code.SPECIFICATION_CONFLICT,
                  ref,
                  row.location(),
                  "NoMount and DNP disagree; placement unspecified"));
          placement = AssemblyStatus.UNSPECIFIED;
        }
        // 입력에 있는 속성만 보존한다. 단위를 모르면 숫자를 지어내지 않고 raw text를 남긴다.
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
          if (Set.of(
                  "reference",
                  "value",
                  "quantity",
                  "manufacturer",
                  "mpn",
                  "type",
                  "Id",
                  "dnp",
                  "nomount")
              .contains(entry.getKey())) continue;
          if (clean(entry.getValue()) != null) {
            attributes.put(
                entry.getKey(),
                normalize(entry.getKey(), entry.getValue(), ref, row.location(), issues));
          }
        }
        // R/C/L Value만 확인된 종류에 맞게 물리 속성으로 해석한다. IC Value를 저항값 등으로 해석하지 않는다.
        String physical =
            switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
              case "resistor" -> "Resistance";
              case "capacitor" -> "Capacitance";
              case "inductor" -> "Inductance";
              default -> null;
            };
        if (physical != null && value != null) {
          var fromValue = normalize(physical, fields.get("value"), ref, row.location(), issues);
          var explicit = attributes.putIfAbsent(physical, fromValue);
          if (explicit != null
              && explicit.normalized() != null
              && fromValue.normalized() != null
              && explicit.normalized().compareTo(fromValue.normalized()) != 0)
            issues.add(
                new ImportIssue(
                    Severity.WARNING,
                    Code.VALUE_MISMATCH,
                    ref,
                    row.location(),
                    "Explicit "
                        + physical
                        + " column differs from Value; explicit attribute retained"));
        }
        String manufacturer = clean(fields.get("manufacturer"));
        String mpn = clean(fields.get("mpn"));
        var candidate =
            new PartSpecification(UUID.randomUUID(), manufacturer, mpn, type, attributes);
        PartSpecification spec = candidate;
        String cataloguePart = clean(fields.get("Part Number"));
        if (mpn != null || cataloguePart != null) {
          // EGP 내부 부품번호를 제조사 MPN으로 임의 승격하지 않는다.
          var key =
              mpn != null
                  ? List.of("mpn", manufacturer == null ? "" : manufacturer, mpn)
                  : List.of("catalogue", cataloguePart, fields.getOrDefault("Rev", ""));
          // 동일 부품 식별자라도 속성이 충돌하면 별도 사양을 보존하고 이슈를 남긴다.
          var variants = byMpn.computeIfAbsent(key, ignored -> new ArrayList<>());
          var existing =
              variants.stream().filter(s -> equivalentSpecification(s, candidate)).findFirst();
          if (existing.isPresent()) spec = existing.get();
          else {
            if (!variants.isEmpty())
              issues.add(
                  new ImportIssue(
                      Severity.WARNING,
                      Code.SPECIFICATION_CONFLICT,
                      ref,
                      row.location(),
                      "Same part identity has conflicting source attributes; separate specification retained"));
            variants.add(candidate);
          }
        }
        specs.put(spec.id(), spec);
        components.put(
            ref,
            new ComponentInstance(
                ref,
                value,
                spec.id(),
                Map.of("bom", row.raw(), "provenance", Map.of("bomLocation", row.location())),
                row.location(),
                placement));
      }
    }
    return new BomResult(components, specs, issues);
  }

  // 공개 카탈로그 BOM의 RefDes/QtyOnBoard 조합에서만 특수 컬럼 의미를 적용한다.
  private static boolean catalogueHeaders(Collection<String> headers) {
    var normalized =
        headers.stream()
            .map(h -> h.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
            .toList();
    return normalized.contains("refdes") && normalized.contains("qtyonboard");
  }

  // 내부 PartNumber는 제조사 MPN이 아니며 이 양식의 Voltage도 자동 정격으로 승격하지 않는다.
  private static String column(String header, boolean catalogue) {
    if (catalogue && header.trim().equalsIgnoreCase("PartNumber")) return "Part Number";
    if (catalogue && header.trim().equalsIgnoreCase("Voltage"))
      return "Voltage"; // 원문만 보존하고 정격 의미는 추정하지 않는다.
    return canonical(header);
  }

  // 실제 Sparrow 규칙: NM=미실장, 빈 NoMount=실장. 다른 문자열은 확인 불가로 보존한다.
  private static AssemblyStatus noMount(
      String raw, String ref, String source, List<ImportIssue> issues) {
    if (raw == null || raw.isBlank()) return AssemblyStatus.FITTED; // 공개 원본에서 확인한 NoMount 플래그 규칙이다.
    if (raw.trim().equalsIgnoreCase("NM")) return AssemblyStatus.DNP;
    issues.add(
        new ImportIssue(
            Severity.WARNING,
            Code.PARSE_FAILURE,
            ref,
            source,
            "Unknown NoMount flag; placement unspecified"));
    return AssemblyStatus.UNSPECIFIED;
  }

  // 일반 DNP 컬럼은 빈 값이면 실장 여부를 확인할 수 없다. 명시적 yes/no만 확정한다.
  private static AssemblyStatus assemblyStatus(
      String raw, String ref, String source, List<ImportIssue> issues) {
    if (clean(raw) == null) return AssemblyStatus.UNSPECIFIED;
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "1", "true", "yes", "y", "dnp", "dnf", "x" -> AssemblyStatus.DNP;
      case "0", "false", "no", "n" -> AssemblyStatus.FITTED;
      default -> {
        issues.add(
            new ImportIssue(
                Severity.WARNING,
                Code.PARSE_FAILURE,
                ref,
                source,
                "Unrecognized DNP state; placement unspecified"));
        yield AssemblyStatus.UNSPECIFIED;
      }
    };
  }

  // SI 단위/숫자가 같으면 1k와1000을 같은 사양으로 볼 수 있다. 미해석 문자열은 원문이 같아야 한다.
  private static boolean equivalentSpecification(PartSpecification a, PartSpecification b) {
    if (!Objects.equals(a.type(), b.type())
        || !a.attributes().keySet().equals(b.attributes().keySet())) return false;
    return a.attributes().entrySet().stream()
        .allMatch(
            e -> {
              var x = e.getValue();
              var y = b.attributes().get(e.getKey());
              return x.normalized() != null && y.normalized() != null
                  ? Objects.equals(x.unit(), y.unit())
                      && x.normalized().compareTo(y.normalized()) == 0
                  : x.equals(y);
            });
  }

  // 속성 의미에 맞는 SI 단위를 선택하고 변환 실패는 경고와 원문으로 전달한다.
  private static AttributeValue normalize(
      String key, String raw, String ref, String source, List<ImportIssue> issues) {
    String unit =
        switch (key) {
          case "Resistance" -> "Ω";
          case "Capacitance" -> "F";
          case "Inductance" -> "H";
          case "Voltage Rating", "Voltage" -> "V";
          case "Current Rating" -> "A";
          case "Tolerance" -> "%";
          default -> null;
        };
    if (unit == null) return AttributeValue.text(raw);
    try {
      return EngineeringUnits.normalize(raw, unit);
    } catch (IllegalArgumentException e) {
      issues.add(new ImportIssue(Severity.WARNING, Code.INVALID_UNIT, ref, source, e.getMessage()));
      return AttributeValue.text(raw);
    }
  }

  private static String inferType(String ref) {
    if (ref.matches("R[0-9]+")) return "resistor";
    if (ref.matches("C[0-9]+")) return "capacitor";
    if (ref.matches("L[0-9]+")) return "inductor";
    return null;
  }

  private static String clean(String value) {
    return value == null || value.isBlank() || value.trim().equals("~") ? null : value.trim();
  }

  // UTF-8/BOM과 quoting을 처리한다. record 번호는 개행을 포함한 셀의 실제 행 번호와 다를 수 있다.
  private static List<Row> csvRows(byte[] bytes) throws IOException {
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    String firstLine =
        text.lines()
            .filter(l -> !l.isBlank())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Empty BOM"));
    char delimiter = detectDelimiter(firstLine);
    var result = new ArrayList<Row>();
    try (var parser =
        CSVFormat.DEFAULT
            .builder()
            .setDelimiter(delimiter)
            .setIgnoreEmptyLines(true)
            .get()
            .parse(new StringReader(text))) {
      List<String> headers = null;
      int cellBudget = 0;
      for (var record : parser) {
        if (record.getRecordNumber() > ParserLimits.MAX_ROWS)
          throw new IllegalArgumentException("BOM row limit exceeded");
        List<String> cells = record.toList();
        cellBudget += cells.size();
        if (cellBudget > ParserLimits.MAX_CELLS)
          throw new IllegalArgumentException("BOM cell limit exceeded");
        if (cells.size() > ParserLimits.MAX_COLUMNS)
          throw new IllegalArgumentException("BOM column limit exceeded");
        if (headers == null) {
          headers = checkedHeaders(cells);
          continue;
        }
        var row = rowMap(headers, cells);
        if (row.values().stream().anyMatch(v -> !v.isBlank()))
          result.add(new Row("csv:record:" + record.getRecordNumber(), row));
        for (int i = headers.size(); i < cells.size(); i++) {
          if (!cells.get(i).isBlank())
            throw new IllegalArgumentException(
                "Unexpected extra CSV field at record " + record.getRecordNumber());
        }
      }
      if (headers == null) throw new IllegalArgumentException("Empty BOM");
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    return result;
  }

  // 헤더의 따옴표 밖 구분자만 센다. 셀 안의 쉼표를 CSV 구분자로 오인하지 않는다.
  private static char detectDelimiter(String line) {
    boolean quoted = false;
    int comma = 0, semicolon = 0, tab = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') i++;
        else quoted = !quoted;
      } else if (!quoted) {
        if (c == ',') comma++;
        if (c == ';') semicolon++;
        if (c == '\t') tab++;
      }
    }
    return tab > comma && tab > semicolon ? '\t' : semicolon > comma ? ';' : ',';
  }

  // Reference 컬럼과 이름 충돌을 검증해 한 컬럼이 다른 컬럼 값을 조용히 덮어쓰지 못하게 한다.
  private static List<String> checkedHeaders(List<String> cells) {
    if (cells.stream().noneMatch(c -> canonical(c).equals("reference")))
      throw new IllegalArgumentException("BOM Reference column required");
    var seen = new HashSet<String>();
    for (String header : cells) {
      if (!header.isBlank() && !seen.add(column(header, catalogueHeaders(cells))))
        throw new IllegalArgumentException("Ambiguous duplicate BOM column: " + header);
    }
    return List.copyOf(cells);
  }

  // 빈 셀 위치를 유지한다. 데이터가 있는 무명/초과 컬럼은 유실시키지 않고 입력 오류로 거절한다.
  private static Map<String, String> rowMap(List<String> headers, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (!cells.get(i).isBlank() && (i >= headers.size() || headers.get(i).isBlank()))
        throw new IllegalArgumentException("Populated BOM cell has no column header");
    }
    var row = new LinkedHashMap<String, String>();
    for (int i = 0; i < headers.size(); i++) {
      if (!headers.get(i).isBlank()) row.put(headers.get(i), i < cells.size() ? cells.get(i) : "");
    }
    return row;
  }

  // 확장자뿐 아니라 파일 magic과 실제 workbook 종류를 검증한다. 시트의 표시 문자열/수식 결과를 읽는다.
  private static List<Row> excelRows(byte[] bytes, boolean xlsx) throws IOException {
    var magic = org.apache.poi.poifs.filesystem.FileMagic.valueOf(bytes);
    if (magic
        != (xlsx
            ? org.apache.poi.poifs.filesystem.FileMagic.OOXML
            : org.apache.poi.poifs.filesystem.FileMagic.OLE2))
      throw new IllegalArgumentException("Excel content does not match filename extension");
    if (xlsx) validateArchive(bytes);
    var result = new ArrayList<Row>();
    var formatter = new DataFormatter(Locale.ROOT);
    boolean found = false;
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if ((workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook) != xlsx)
        throw new IllegalArgumentException("Excel content does not match filename extension");
      var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      int cellBudget = 0;
      for (var sheet : workbook) {
        List<String> headers = null;
        for (var row : sheet) {
          var cells = new ArrayList<String>();
          int count = Math.max(row.getLastCellNum(), headers == null ? 0 : headers.size());
          if (count > ParserLimits.MAX_COLUMNS || row.getRowNum() >= ParserLimits.MAX_ROWS)
            throw new IllegalArgumentException("BOM row/column limit exceeded");
          cellBudget += Math.max(0, count);
          if (cellBudget > ParserLimits.MAX_CELLS)
            throw new IllegalArgumentException("BOM cell limit exceeded");
          // 중간 빈 셀도 순서대로 읽어 컬럼이 왼쪽으로 밀리지 않게 한다.
          for (int i = 0; i < count; i++)
            cells.add(formatter.formatCellValue(row.getCell(i), evaluator));
          if (headers == null) {
            if (cells.stream().anyMatch(c -> canonical(c).equals("reference"))) {
              headers = checkedHeaders(cells);
              found = true;
            }
            continue;
          }
          var raw = rowMap(headers, cells);
          if (raw.values().stream().anyMatch(v -> !v.isBlank()))
            result.add(
                new Row("sheet:" + sheet.getSheetName() + ":row:" + (row.getRowNum() + 1), raw));
        }
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid or unsupported Excel content", e);
    }
    if (!found) throw new IllegalArgumentException("BOM Reference column required");
    return result;
  }

  /** POI의 Workbook 할당 전에 압축 해제 크기를 검사한다. POI 자체 ZIP/XML 검사도 유지한다. */
  // 작은 XLSX ZIP이 대량으로 확장되는 경우를 POI 객체 생성 전에 차단한다.
  private static void validateArchive(byte[] bytes) throws IOException {
    int entries = 0;
    long expanded = 0;
    byte[] buffer = new byte[8192];
    try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
      while (zip.getNextEntry() != null) {
        if (++entries > 1000) throw new IllegalArgumentException("Excel ZIP entry limit exceeded");
        int count;
        while ((count = zip.read(buffer)) != -1) {
          expanded += count;
          if (expanded > ParserLimits.MAX_EXPANDED_BYTES)
            throw new IllegalArgumentException("Excel expanded content limit exceeded");
        }
      }
    }
  }
}
