package com.hls.loader;

import com.hls.model.Block;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loader coverage for the optional {@code ODM} sheet: a happy-path parse of all
 * four attribute columns, and a representative validation failure.
 */
class ExcelBlockRepositoryOdmTest {

    @Test
    void parsesAllOdmAttributes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("odm_ok.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            writeBlocks(wb);
            writeTdm(wb);
            writePdm(wb);
            // ODM: column A = block id; headers SG / Day / Hour_Start / Parallelism.
            Sheet odm = wb.createSheet("ODM");
            header(odm, "HL Block", "SG", "Day", "Hour_Start", "Parallelism");
            row(odm, 1, "B1", "SG001", "1", "2", "No");
            row(odm, 2, "B2", "sg001", "", "", "Yes");
            write(wb, file);
        }

        ExcelBlockRepository repo = new ExcelBlockRepository(file.toString());
        Block b1 = repo.getBlockById("B1");
        Block b2 = repo.getBlockById("B2");

        assertThat(b1.odm().sequenceGroup()).isEqualTo("sg001"); // normalized lower-case
        assertThat(b1.odm().pinnedDay()).isEqualTo(1);
        assertThat(b1.odm().pinnedStartHour()).isEqualTo(2);
        assertThat(b1.odm().noParallel()).isTrue();

        assertThat(b2.odm().sequenceGroup()).isEqualTo("sg001");
        assertThat(b2.odm().pinnedDay()).isNull();
        assertThat(b2.odm().pinnedStartHour()).isNull();
        assertThat(b2.odm().noParallel()).isFalse();
    }

    /**
     * Real sheets spell the hour-pin header both ways. An unmatched header is
     * silently skipped, which would drop every hour pin without warning, so
     * both spellings must resolve to the same column.
     */
    @Test
    void acceptsPluralHoursStartHeader(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("odm_plural.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            writeBlocks(wb);
            writeTdm(wb);
            writePdm(wb);
            Sheet odm = wb.createSheet("ODM");
            header(odm, "ODM", "SG", "Day", "Hours_Start", "Parallelism");
            row(odm, 1, "B1", "", "3", "11", "");
            write(wb, file);
        }

        Block b1 = new ExcelBlockRepository(file.toString()).getBlockById("B1");
        assertThat(b1.odm().pinnedDay()).isEqualTo(3);
        assertThat(b1.odm().pinnedStartHour()).isEqualTo(11);
    }

    @Test
    void rejectsInvalidParallelism(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("odm_bad.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            writeBlocks(wb);
            writeTdm(wb);
            writePdm(wb);
            Sheet odm = wb.createSheet("ODM");
            header(odm, "HL Block", "SG", "Day", "Hour_Start", "Parallelism");
            row(odm, 1, "B1", "", "", "", "maybe");
            write(wb, file);
        }

        assertThatThrownBy(() -> new ExcelBlockRepository(file.toString()))
                .isInstanceOf(LoaderValidationException.class)
                .satisfies(ex -> assertThat(((LoaderValidationException) ex).getReport().getViolations())
                        .anyMatch(v -> v.code().equals("ODM_PARALLELISM_INVALID")));
    }

    // ── minimal valid workbook scaffolding (two blocks, no edges, no zones) ──

    private static void writeBlocks(Workbook wb) {
        Sheet s = wb.createSheet("Blocks");
        header(s, "HL Block", "HRS", "FTE", "TOOLS", "TOOLS AMOUNT");
        row(s, 1, "B1", "2", "1", "", "");
        row(s, 2, "B2", "3", "1", "", "");
    }

    private static void writeTdm(Workbook wb) {
        Sheet s = wb.createSheet("TDM");
        header(s, "", "B1", "B2");
        row(s, 1, "B1", "", "");
        row(s, 2, "B2", "", "");
    }

    private static void writePdm(Workbook wb) {
        Sheet s = wb.createSheet("PDM");
        header(s, "", "Z1");
        row(s, 1, "B1", "");
        row(s, 2, "B2", "");
    }

    private static void header(Sheet sheet, String... values) {
        row(sheet, 0, values);
    }

    private static void row(Sheet sheet, int rowIdx, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int c = 0; c < values.length; c++) {
            if (!values[c].isEmpty()) {
                row.createCell(c).setCellValue(values[c]);
            }
        }
    }

    private static void write(Workbook wb, Path file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            wb.write(out);
        }
    }
}
