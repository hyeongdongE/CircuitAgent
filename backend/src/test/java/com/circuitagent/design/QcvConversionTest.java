package com.circuitagent.design;

import com.circuitagent.design.tools.CsvToXlsx;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class QcvConversionTest {
    @Test void feCsvAndConvertedExcelAreCellForCellIdentical() throws Exception {
        var source = CsvToXlsx.rows(QcvFixtures.file("fe", ".csv"));
        assertThat(source).hasSize(70);
        assertThat(source.getFirst()).containsExactly("PartNumber","Rev","RefDes","QtyOnBoard","NoMount","Value","Voltage","Part Name");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(QcvFixtures.file("fe", ".xlsx")))) {
            var sheet = workbook.getSheetAt(0); assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(70);
            for (int i=0;i<source.size();i++) {
                assertThat(sheet.getRow(i).getLastCellNum()).isEqualTo((short)source.get(i).size());
                for (int j=0;j<source.get(i).size();j++) assertThat(sheet.getRow(i).getCell(j).getStringCellValue()).isEqualTo(source.get(i).get(j));
            }
        }
    }
    @Test void converterPreservesZerosSpacesEmptyCellsAndQuotedSemicolonsAsStrings() throws Exception {
        byte[] csv="ID;Value;Empty;Name\n0001; 10u ;;\"part;name\"\n".getBytes(StandardCharsets.UTF_8);
        try (var workbook=WorkbookFactory.create(new ByteArrayInputStream(CsvToXlsx.convert(csv)))) {
            var row=workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("0001");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo(" 10u ");
            assertThat(row.getCell(2).getStringCellValue()).isEmpty();
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("part;name");
        }
    }
}
