package com.circuitagent.design;

import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BomParserTest {
    private final BomParser parser = new BomParser();
    static byte[] fixture(String file) throws Exception {
        try (var in = BomParserTest.class.getResourceAsStream("/fixtures/" + file)) { return in.readAllBytes(); }
    }
    @Test
    void real5vBomExpandsAll12ReferencesWithoutInventingSpecifications() throws Exception {
        var result = parser.parse(fixture("5v/5Volt_Power_Supply.csv"), "bom.csv");
        assertThat(result.components()).hasSize(12).containsKeys("J1", "J2", "D1", "D2", "D3", "D4", "U1", "R1");
        assertThat(result.issues()).isEmpty();
        var r1 = result.components().get("R1");
        var spec = result.specifications().get(r1.partSpecificationId());
        assertThat(r1.value()).isEqualTo("1k");
        assertThat(spec.attributes().get("Resistance").normalized()).isEqualByComparingTo("1000");
        assertThat(spec.attributes().get("Resistance").unit()).isEqualTo("Ω");
        assertThat(r1.rawSources().get("bom").get("Designation")).isEqualTo("1k");
        assertThat(result.components().get("D1").rawSources().get("bom").get("Designator")).isEqualTo("D4,D3,D2,D1");
        var u1 = result.components().get("U1");
        assertThat(u1.value()).isEqualTo("L7805");
        var uSpec = result.specifications().get(u1.partSpecificationId());
        assertThat(uSpec.manufacturer()).isNull();
        assertThat(uSpec.mpn()).isNull();
        assertThat(uSpec.attributes()).doesNotContainKeys("Voltage Rating", "Current Rating", "Resistance");
        assertThat(result.specifications().get(result.components().get("C1").partSpecificationId())
            .attributes().get("Capacitance").normalized()).isEqualByComparingTo("0.000470");
    }
    @Test
    void realShiftBomHas27InstancesAndReusesExplicitMpn() throws Exception {
        var result = parser.parse(fixture("shift/Shift_Indicator_PCB_BOM.csv"), "bom.csv");
        assertThat(result.components()).hasSize(27);
        assertThat(result.components().get("R3").partSpecificationId()).isEqualTo(result.components().get("R9").partSpecificationId());
        assertThat(result.components().get("D3").partSpecificationId()).isEqualTo(result.components().get("D5").partSpecificationId());
        assertThat(result.components().get("D3").value()).isEqualTo("Green LED 1");
        assertThat(result.components().get("D5").value()).isEqualTo("Green LED 3");
        assertThat(result.components()).containsKey("SN65HVDA195");
        assertThat(result.issues()).extracting(ImportIssue::code).containsExactly(ImportIssue.Code.MISSING_VALUE);
    }
    @Test
    void duplicateMissingQuantityAndMalformedUnitsAreReported() throws Exception {
        String text = "Reference,Value,Qty,Manufacturer,MPN,custom\nR1,wrong,1,,,original\nR1,10k,1,,,\n,20k,1,,,\nR2,,2,,,\n";
        var result = parser.parse(text.getBytes(StandardCharsets.UTF_8), "bom.csv");
        assertThat(result.components()).hasSize(2);
        assertThat(result.issues()).extracting(ImportIssue::code).contains(ImportIssue.Code.DUPLICATE_REFERENCE,
            ImportIssue.Code.MISSING_REFERENCE, ImportIssue.Code.QUANTITY_MISMATCH, ImportIssue.Code.MISSING_VALUE, ImportIssue.Code.INVALID_UNIT);
        var bad = result.specifications().get(result.components().get("R1").partSpecificationId());
        assertThat(bad.attributes().get("Resistance").raw()).isEqualTo("wrong");
        assertThat(bad.attributes().get("Resistance").normalized()).isNull();
        assertThat(bad.attributes().get("custom").raw()).isEqualTo("original");
        assertThat(result.specifications().get(result.components().get("R2").partSpecificationId()).attributes()).doesNotContainKey("Resistance");
    }
    @Test
    void supplementaryExcelFormatsHandlePreambleSheetsAndNumericCells() throws Exception {
        for (Workbook workbook : List.of(new XSSFWorkbook(), new HSSFWorkbook())) {
            try (workbook; var out = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("Parts");
                sheet.createRow(0).createCell(0).setCellValue("BOM export");
                var header = sheet.createRow(1);
                header.createCell(0).setCellValue("References"); header.createCell(1).setCellValue("Value"); header.createCell(2).setCellValue("Quantity");
                var row = sheet.createRow(2);
                row.createCell(0).setCellValue("R1 R2"); row.createCell(1).setCellValue("10kΩ"); row.createCell(2).setCellValue(2);
                workbook.createSheet("Notes").createRow(0).createCell(0).setCellValue("notes");
                workbook.write(out);
                var result = parser.parse(out.toByteArray(), workbook instanceof XSSFWorkbook ? "bom.xlsx" : "bom.xls");
                assertThat(result.components()).hasSize(2);
                assertThat(result.issues()).isEmpty();
                assertThat(result.components().get("R1").sourcePath()).isEqualTo("sheet:Parts:row:3");
            }
        }
    }
    @Test
    void engineeringPrefixesAreCaseSensitiveAndBadInputsDoNotBecomeZero() {
        assertThat(EngineeringUnits.normalize("1MΩ", "Ω").normalized()).isEqualByComparingTo("1000000");
        assertThat(EngineeringUnits.normalize("1mΩ", "Ω").normalized()).isEqualByComparingTo("0.001");
        assertThat(EngineeringUnits.normalize("4k7", "Ω").normalized()).isEqualByComparingTo("4700");
        assertThat(EngineeringUnits.normalize("100nF", "F").normalized()).isEqualByComparingTo("0.0000001");
        assertThatThrownBy(() -> EngineeringUnits.normalize("1kF", "Ω")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void rejectsAmbiguousHeadersAndInvalidUtf8() {
        assertThatThrownBy(() -> parser.parse("Reference,Designator,Value\nR1,R2,10k".getBytes(StandardCharsets.UTF_8), "bom.csv"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ambiguous");
        assertThatThrownBy(() -> parser.parse(new byte[]{(byte) 0xff}, "bom.csv")).isInstanceOf(java.io.IOException.class);
    }
}
