package com.hls.loader;

import org.apache.poi.ss.util.CellReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates {@link LoaderViolation}s found during a single Excel-load attempt.
 * The loader runs in collect-then-throw mode: every violation is appended here,
 * and at the end of the load pass the report is wrapped in a
 * {@link LoaderValidationException} if {@link #hasViolations()} is true.
 */
public class LoaderValidationReport {

    private final List<LoaderViolation> violations = new ArrayList<>();

    public void add(String sheet, int rowIdx, int colIdx, String code, String message) {
        violations.add(new LoaderViolation(sheet, cellRef(rowIdx, colIdx), code, message));
    }

    public void addAtCellRef(String sheet, String cellRef, String code, String message) {
        violations.add(new LoaderViolation(sheet, cellRef, code, message));
    }

    public void addRow(String sheet, int rowIdx, String code, String message) {
        // Whole-row violation: use the row label only, e.g. "row 7" (1-based for humans).
        violations.add(new LoaderViolation(sheet, "row " + (rowIdx + 1), code, message));
    }

    public void addSheetLevel(String sheet, String code, String message) {
        violations.add(new LoaderViolation(sheet, null, code, message));
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }

    public int size() {
        return violations.size();
    }

    public List<LoaderViolation> getViolations() {
        return Collections.unmodifiableList(violations);
    }

    /** Multi-line human-readable rendering for log output and exception messages. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("Excel data validation failed with ")
                .append(violations.size())
                .append(violations.size() == 1 ? " violation:" : " violations:");
        for (LoaderViolation v : violations) {
            sb.append("\n  ").append(v.toString());
        }
        return sb.toString();
    }

    /** Convert (rowIdx, colIdx) — both zero-based — to A1-style notation (e.g. (6, 2) → "C7"). */
    public static String cellRef(int rowIdx, int colIdx) {
        return new CellReference(rowIdx, colIdx).formatAsString();
    }
}
