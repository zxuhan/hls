package com.hls.loader;

/**
 * One data-validation problem found while loading the Excel workbook.
 *
 * @param sheet     sheet name as it appears in the workbook (e.g. "Blocks", "TDM", "PDM")
 * @param cellRef   A1-style cell coordinate (e.g. "C7"), or a row/column label string
 *                  for matrix-shaped sheets when the offending location is best
 *                  described by labels rather than indices. May be {@code null} for
 *                  workbook-level or sheet-level problems.
 * @param code      short machine-readable code (e.g. "BLOCKS_MISSING_HEADER")
 * @param message   human-readable description with the offending value and what was expected
 */
public record LoaderViolation(String sheet, String cellRef, String code, String message) {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        sb.append(sheet);
        if (cellRef != null && !cellRef.isEmpty()) {
            sb.append('!').append(cellRef);
        }
        sb.append("] ").append(code).append(": ").append(message);
        return sb.toString();
    }
}
