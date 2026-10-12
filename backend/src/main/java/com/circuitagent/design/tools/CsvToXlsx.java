package com.circuitagent.design.tools;

import com.circuitagent.design.parse.ParserLimits;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Lossless semicolon CSV conversion: strings, whitespace, empty cells and every row. */
public final class CsvToXlsx {
    private CsvToXlsx() {}
    public static List<List<String>> rows(byte[] csv) throws IOException {
        ParserLimits.file(csv);
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(csv)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        try (var parser = CSVFormat.DEFAULT.builder().setDelimiter(';').setIgnoreEmptyLines(false).get().parse(new StringReader(text))) {
            var rows = new ArrayList<List<String>>(); int cells = 0;
            for (var r : parser) {
                var values = r.toList(); cells += values.size();
                if (rows.size() >= ParserLimits.MAX_ROWS || values.size() > ParserLimits.MAX_COLUMNS || cells > ParserLimits.MAX_CELLS)
                    throw new IllegalArgumentException("CSV conversion budget exceeded");
                rows.add(values);
            }
            if (rows.isEmpty()) throw new IllegalArgumentException("Empty CSV");
            return List.copyOf(rows);
        } catch (UncheckedIOException e) { throw e.getCause(); }
    }
    public static byte[] convert(byte[] csv) throws IOException {
        var rows = rows(csv);
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("BOM");
            for (int i = 0; i < rows.size(); i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < rows.get(i).size(); j++) row.createCell(j, CellType.STRING).setCellValue(rows.get(i).get(j));
            }
            workbook.write(bytes);
            return bytes.toByteArray();
        }
    }
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        for (String board : List.of("fe", "ntop", "fe-ntop")) {
            Path csv = dir.resolve(board + ".csv");
            if (!Files.exists(csv)) throw new IllegalArgumentException("Download fixtures first: python3 scripts/fetch-qcv-fixtures.py");
            byte[] input = Files.readAllBytes(csv), output = convert(input);
            Files.write(dir.resolve(board + ".xlsx"), output);
            // Reopen and compare every original cell before accepting the conversion.
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
                var source = rows(input); var sheet = workbook.getSheetAt(0);
                if (sheet.getPhysicalNumberOfRows() != source.size()) throw new IllegalStateException("Conversion row mismatch");
                for (int i = 0; i < source.size(); i++) for (int j = 0; j < source.get(i).size(); j++)
                    if (!source.get(i).get(j).equals(sheet.getRow(i).getCell(j).getStringCellValue())) throw new IllegalStateException("Conversion cell mismatch");
            }
            System.out.println(board + ": verified " + rows(input).size() + " rows including header");
        }
    }
}
