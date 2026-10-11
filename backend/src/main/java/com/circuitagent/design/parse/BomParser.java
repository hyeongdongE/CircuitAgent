package com.circuitagent.design.parse;

import com.circuitagent.design.model.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.circuitagent.design.model.ImportIssue.*;

@Component
public class BomParser {
    private record Row(String location, Map<String, String> raw) {}
    private static String canonical(String header) {
        String key = header.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (key) {
            case "reference", "references", "ref", "refs", "designator", "designators" -> "reference";
            case "value", "designation" -> "value";
            case "qty", "quantity" -> "quantity";
            case "mpn", "partnumber", "manufacturerpartnumber" -> "mpn";
            case "manufacturer", "mfr" -> "manufacturer";
            case "type", "componenttype" -> "type";
            case "voltagerating", "voltage" -> "Voltage Rating";
            case "currentrating", "current" -> "Current Rating";
            case "resistance" -> "Resistance";
            case "capacitance" -> "Capacitance";
            case "inductance" -> "Inductance";
            case "tolerance" -> "Tolerance";
            default -> header.trim();
        };
    }

    public BomResult parse(byte[] bytes, String filename) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        List<Row> rows;
        if (lower.endsWith(".csv")) rows = csvRows(bytes);
        else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) rows = excelRows(bytes);
        else throw new IllegalArgumentException("Supported BOM formats: CSV, XLSX, XLS");
        Map<String, ComponentInstance> components = new LinkedHashMap<>();
        Map<UUID, PartSpecification> specs = new LinkedHashMap<>();
        Map<List<String>, PartSpecification> byMpn = new HashMap<>();
        List<ImportIssue> issues = new ArrayList<>();
        for (var row : rows) {
            Map<String, String> fields = new LinkedHashMap<>();
            row.raw().forEach((k, v) -> fields.put(canonical(k), v));
            String references = clean(fields.get("reference"));
            if (references == null) {
                issues.add(new ImportIssue(Severity.ERROR, Code.MISSING_REFERENCE, null, row.location(), "Missing Reference"));
                continue;
            }
            // Reference ranges are deliberately not guessed; explicit references remain exact.
            var refs = Arrays.stream(references.split("[,;\\s]+"))
                .filter(s -> !s.isBlank()).toList();
            String quantity = clean(fields.get("quantity"));
            if (quantity != null) {
                try {
                    if (new java.math.BigDecimal(quantity).intValueExact() != refs.size())
                        issues.add(new ImportIssue(Severity.WARNING, Code.QUANTITY_MISMATCH, references, row.location(), "Quantity differs from explicit Reference count"));
                } catch (ArithmeticException | NumberFormatException e) {
                    issues.add(new ImportIssue(Severity.ERROR, Code.PARSE_FAILURE, references, row.location(), "Invalid quantity: " + quantity));
                }
            }
            for (String ref : refs) {
                if (components.containsKey(ref)) {
                    issues.add(new ImportIssue(Severity.ERROR, Code.DUPLICATE_REFERENCE, ref, row.location(), "Duplicate Reference; first instance retained"));
                    continue;
                }
                String value = clean(fields.get("value"));
                if (value == null) issues.add(new ImportIssue(Severity.WARNING, Code.MISSING_VALUE, ref, row.location(), "Missing component value"));
                String type = clean(fields.get("type"));
                if (type == null) type = inferType(ref);
                Map<String, AttributeValue> attributes = new LinkedHashMap<>();
                for (var entry : fields.entrySet()) {
                    if (Set.of("reference", "value", "quantity", "manufacturer", "mpn", "type", "Id").contains(entry.getKey())) continue;
                    if (clean(entry.getValue()) != null) {
                        attributes.put(entry.getKey(), normalize(entry.getKey(), entry.getValue(), ref, row.location(), issues));
                    }
                }
                String physical = switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
                    case "resistor" -> "Resistance";
                    case "capacitor" -> "Capacitance";
                    case "inductor" -> "Inductance";
                    default -> null;
                };
                if (physical != null && value != null && !attributes.containsKey(physical))
                    attributes.put(physical, normalize(physical, value, ref, row.location(), issues));
                String manufacturer = clean(fields.get("manufacturer"));
                String mpn = clean(fields.get("mpn"));
                var candidate = new PartSpecification(UUID.randomUUID(), manufacturer, mpn, type, attributes);
                PartSpecification spec = candidate;
                if (mpn != null) {
                    var key = List.of(manufacturer == null ? "" : manufacturer, mpn);
                    var existing = byMpn.putIfAbsent(key, candidate);
                    if (existing != null) {
                        if (Objects.equals(existing.type(), type) && existing.attributes().equals(attributes)) spec = existing;
                        else issues.add(new ImportIssue(Severity.WARNING, Code.SPECIFICATION_CONFLICT, ref, row.location(),
                            "Same manufacturer/MPN has conflicting source attributes; separate specification retained"));
                    }
                }
                specs.put(spec.id(), spec);
                components.put(ref, new ComponentInstance(ref, value, spec.id(), Map.of("bom", row.raw()), row.location()));
            }
        }
        return new BomResult(components, specs, issues);
    }

    private static AttributeValue normalize(String key, String raw, String ref, String source, List<ImportIssue> issues) {
        String unit = switch (key) {
            case "Resistance" -> "Ω";
            case "Capacitance" -> "F";
            case "Inductance" -> "H";
            case "Voltage Rating" -> "V";
            case "Current Rating" -> "A";
            case "Tolerance" -> "%";
            default -> null;
        };
        if (unit == null) return AttributeValue.text(raw);
        try { return EngineeringUnits.normalize(raw, unit); }
        catch (IllegalArgumentException e) {
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
    private static String clean(String value) { return value == null || value.isBlank() || value.trim().equals("~") ? null : value.trim(); }

    private static List<Row> csvRows(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String firstLine = text.lines().filter(l -> !l.isBlank()).findFirst().orElseThrow(() -> new IllegalArgumentException("Empty BOM"));
        char delimiter = detectDelimiter(firstLine);
        var result = new ArrayList<Row>();
        try (var parser = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).get().parse(new StringReader(text))) {
            List<String> headers = null;
            for (var record : parser) {
                List<String> cells = record.toList();
                if (headers == null) { headers = checkedHeaders(cells); continue; }
                var row = rowMap(headers, cells);
                if (row.values().stream().anyMatch(v -> !v.isBlank())) result.add(new Row("csv:record:" + record.getRecordNumber(), row));
                for (int i = headers.size(); i < cells.size(); i++) {
                    if (!cells.get(i).isBlank()) throw new IllegalArgumentException("Unexpected extra CSV field at record " + record.getRecordNumber());
                }
            }
            if (headers == null) throw new IllegalArgumentException("Empty BOM");
        } catch (UncheckedIOException e) { throw e.getCause(); }
        return result;
    }

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
    private static List<String> checkedHeaders(List<String> cells) {
        if (cells.stream().noneMatch(c -> canonical(c).equals("reference"))) throw new IllegalArgumentException("BOM Reference column required");
        var seen = new HashSet<String>();
        for (String header : cells) {
            if (!header.isBlank() && !seen.add(canonical(header))) throw new IllegalArgumentException("Ambiguous duplicate BOM column: " + header);
        }
        return List.copyOf(cells);
    }
    private static Map<String, String> rowMap(List<String> headers, List<String> cells) {
        var row = new LinkedHashMap<String, String>();
        for (int i = 0; i < headers.size(); i++) {
            if (!headers.get(i).isBlank()) row.put(headers.get(i), i < cells.size() ? cells.get(i) : "");
        }
        return row;
    }
    private static List<Row> excelRows(byte[] bytes) throws IOException {
        var result = new ArrayList<Row>();
        var formatter = new DataFormatter(Locale.ROOT);
        boolean found = false;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (var sheet : workbook) {
                List<String> headers = null;
                for (var row : sheet) {
                    var cells = new ArrayList<String>();
                    int count = Math.max(row.getLastCellNum(), headers == null ? 0 : headers.size());
                    for (int i = 0; i < count; i++) cells.add(formatter.formatCellValue(row.getCell(i), evaluator));
                    if (headers == null) {
                        if (cells.stream().anyMatch(c -> canonical(c).equals("reference"))) { headers = checkedHeaders(cells); found = true; }
                        continue;
                    }
                    var raw = rowMap(headers, cells);
                    if (raw.values().stream().anyMatch(v -> !v.isBlank())) result.add(new Row("sheet:" + sheet.getSheetName() + ":row:" + (row.getRowNum() + 1), raw));
                }
            }
        }
        if (!found) throw new IllegalArgumentException("BOM Reference column required");
        return result;
    }
}
