package com.circuitagent.design.tools;

import com.circuitagent.design.parse.ParserLimits;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 공개 세미콜론 BOM CSV를 PoC 입력 XLSX로 손실 없이 변환하는 CLI 도구. 숫자 자동 변환 없이 모든 셀을 문자열로 저장하고 다시 열어 원본과 셀별로 비교한다.
 * 회로 파싱이나 Rule 판정은 하지 않는다.
 */
public final class CsvToXlsx {
  private CsvToXlsx() {}

  // 원본 세미콜론/UTF-8/빈 행 규칙을 유지한다. BOM 정규화 없이 문자열 행을 반환한다.
  public static List<List<String>> rows(byte[] csv) throws IOException {
    ParserLimits.file(csv);
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(csv)).toString();
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    try (var parser =
        CSVFormat.DEFAULT
            .builder()
            .setDelimiter(';')
            .setIgnoreEmptyLines(false)
            .get()
            .parse(new StringReader(text))) {
      var rows = new ArrayList<List<String>>();
      int cells = 0;
      for (var r : parser) {
        var values = r.toList();
        cells += values.size();
        if (rows.size() >= ParserLimits.MAX_ROWS
            || values.size() > ParserLimits.MAX_COLUMNS
            || cells > ParserLimits.MAX_CELLS)
          throw new IllegalArgumentException("CSV conversion budget exceeded");
        rows.add(values);
      }
      if (rows.isEmpty()) throw new IllegalArgumentException("Empty CSV");
      return List.copyOf(rows);
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  public static byte[] convert(byte[] csv) throws IOException {
    var rows = rows(csv);
    try (var workbook = new XSSFWorkbook();
        var bytes = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("BOM");
      for (int i = 0; i < rows.size(); i++) {
        var row = sheet.createRow(i);
        // CellType.STRING은 PartNumber/Reference의 선행0·공백·빈 문자열을 Excel 자동 변환으로 잃지 않게 한다.
        for (int j = 0; j < rows.get(i).size(); j++)
          row.createCell(j, CellType.STRING).setCellValue(rows.get(i).get(j));
      }
      workbook.write(bytes);
      return bytes.toByteArray();
    }
  }

  public static void main(String[] args) throws IOException {
    Path dir = Path.of(args[0]);
    for (String board : List.of("fe", "ntop", "fe-ntop")) {
      Path csv = dir.resolve(board + ".csv");
      if (!Files.exists(csv))
        throw new IllegalArgumentException(
            "Download fixtures first: python3 scripts/fetch-qcv-fixtures.py");
      byte[] input = Files.readAllBytes(csv), output = convert(input);
      Files.write(dir.resolve(board + ".xlsx"), output);
      // 생성한 XLSX를 다시 열어 원본의 모든 셀이 보존되었는지 확인한다.
      try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
        var source = rows(input);
        var sheet = workbook.getSheetAt(0);
        if (sheet.getPhysicalNumberOfRows() != source.size())
          throw new IllegalStateException("Conversion row mismatch");
        for (int i = 0; i < source.size(); i++)
          for (int j = 0; j < source.get(i).size(); j++)
            if (!source.get(i).get(j).equals(sheet.getRow(i).getCell(j).getStringCellValue()))
              throw new IllegalStateException("Conversion cell mismatch");
      }
      System.out.println(board + ": verified " + rows(input).size() + " rows including header");
    }
  }
}
