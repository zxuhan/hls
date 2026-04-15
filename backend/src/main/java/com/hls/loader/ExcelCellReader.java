package com.hls.loader;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;

import java.util.OptionalDouble;

/**
 * Type-safe accessors for Apache POI {@link Cell} values used by the Excel loader.
 *
 * <p>Every method tolerates {@code null} cells and blank cells. Numeric cells are
 * surfaced as {@code double}; string-typed cells are trimmed and emptiness is
 * normalized to {@code null}/{@link OptionalDouble#empty()}.
 */
public final class ExcelCellReader {

    private static final DataFormatter FORMATTER = new DataFormatter();

    private ExcelCellReader() {}

    /**
     * Read a cell as a trimmed non-empty string, or {@code null} if the cell is
     * absent / blank. Numeric cells are converted via the data formatter so an
     * integer-valued cell like {@code 123.0} comes back as {@code "123"}, and
     * a fractional cell like {@code 3.5} comes back as {@code "3.5"}.
     */
    public static String readString(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            double val = cell.getNumericCellValue();
            if (val == Math.floor(val) && !Double.isInfinite(val)) {
                return String.valueOf((long) val);
            }
            return String.valueOf(val);
        }
        String value = FORMATTER.formatCellValue(cell).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Read a cell as a numeric value, or {@link OptionalDouble#empty()} if the
     * cell is null, blank, or non-numeric. Never throws — callers can use the
     * empty result to surface a structured validation message instead of
     * propagating raw {@link NumberFormatException}s.
     */
    public static OptionalDouble readNumericOrEmpty(Cell cell) {
        if (cell == null) return OptionalDouble.empty();
        CellType type = cell.getCellType();
        if (type == CellType.BLANK) return OptionalDouble.empty();
        if (type == CellType.NUMERIC) {
            return OptionalDouble.of(cell.getNumericCellValue());
        }
        if (type == CellType.STRING) {
            String value = cell.getStringCellValue().trim();
            if (value.isEmpty()) return OptionalDouble.empty();
            try {
                return OptionalDouble.of(Double.parseDouble(value));
            } catch (NumberFormatException e) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    /** Whether this cell carries a value that should be treated as "present". */
    public static boolean isPresent(Cell cell) {
        if (cell == null) return false;
        CellType type = cell.getCellType();
        if (type == CellType.BLANK) return false;
        if (type == CellType.STRING) {
            return !cell.getStringCellValue().trim().isEmpty();
        }
        return true;
    }
}
