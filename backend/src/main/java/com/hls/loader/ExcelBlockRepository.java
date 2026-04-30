package com.hls.loader;

import com.hls.model.Block;
import com.hls.model.Part;
import com.hls.model.ToolRequirement;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Loads {@link Block} data from an Excel workbook with three sheets:
 * {@code Blocks}, {@code TDM} (precedence matrix), {@code PDM} (spatial-zone
 * matrix). The full data contract and validation rules live in
 * {@code DATA_SCHEMA.md}.
 *
 * <p>Loading is strict: the loader collects every {@link LoaderViolation} in
 * one pass, then either throws a {@link LoaderValidationException} carrying
 * the full report or returns a fully validated dataset. Algorithms never see
 * a partial or corrupted load.
 */
public class ExcelBlockRepository implements BlockRepository {

    private static final Logger log = LoggerFactory.getLogger(ExcelBlockRepository.class);

    // ── Required sheet names ───────────────────────────────────────────────
    private static final String SHEET_BLOCKS = "Blocks";
    private static final String SHEET_TDM = "TDM";
    private static final String SHEET_PDM = "PDM";
    private static final String SHEET_BLOCKS_PARTS = "Blocks_Parts";

    // ── Required Blocks-sheet headers ──────────────────────────────────────
    private static final String COL_HL_BLOCK = "HL Block";
    private static final String COL_HRS = "HRS";
    private static final String COL_FTE = "FTE";
    private static final String COL_TOOLS = "TOOLS";
    private static final String COL_TOOLS_AMOUNT = "TOOLS AMOUNT";
    private static final String COL_COLOUR = "Colour"; // optional
    private static final List<String> POSITION_AXES = List.of(
            "WS", "(-) WS", "WSSC", "ILL", "RS", "RH Turret", "WH", "RH Library");

    /** Headers that must be present on the Blocks sheet. The 8 position-axis
     * headers and {@code Colour} are optional and handled separately. */
    private static final List<String> REQUIRED_BLOCKS_HEADERS = List.of(
            COL_HL_BLOCK, COL_HRS, COL_FTE, COL_TOOLS, COL_TOOLS_AMOUNT);

    private final Map<String, Block> blockMap;
    private final List<Block> blockList;
    private final List<Part> partList;

    public ExcelBlockRepository(String filePath) {
        LoaderValidationReport report = new LoaderValidationReport();
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            // ── Sheet lookup ───────────────────────────────────────────────
            Sheet blocksSheet = findSheet(workbook, SHEET_BLOCKS, report);
            Sheet tdmSheet = findSheet(workbook, SHEET_TDM, report);
            Sheet pdmSheet = findSheet(workbook, SHEET_PDM, report);
            if (report.hasViolations()) {
                throw new LoaderValidationException(report);
            }

            // ── Per-sheet parse + validate ─────────────────────────────────
            Map<String, RawBlock> rawBlocks = parseBlocksSheet(blocksSheet, report);
            // Even if Blocks failed validation, we still try the matrix sheets so the
            // user sees every problem at once. Cross-validation against the block-id
            // set is best-effort: we use whatever IDs parsed cleanly.
            Set<String> knownBlockIds = rawBlocks.keySet();
            Map<String, List<String>> tdmEdges = parseTdmSheet(tdmSheet, knownBlockIds, report);
            Map<String, Set<String>> pdmZones = parsePdmSheet(pdmSheet, knownBlockIds, report);
            // Optional sheet — null when absent. Loader proceeds with empty parts.
            Sheet partsSheet = findOptionalSheet(workbook, SHEET_BLOCKS_PARTS);
            List<Part> parts = partsSheet == null
                    ? List.of()
                    : parseBlocksPartsSheet(partsSheet, rawBlocks, report);

            if (report.hasViolations()) {
                throw new LoaderValidationException(report);
            }

            // ── Cross-sheet check: precedence acyclicity (rule 18) ─────────
            List<String> cyclePath = findCycle(knownBlockIds, tdmEdges);
            if (cyclePath != null) {
                report.addSheetLevel(SHEET_TDM, "PRECEDENCE_CYCLE",
                        "precedence graph contains a cycle: " + String.join(" → ", cyclePath));
                throw new LoaderValidationException(report);
            }

            this.blockMap = assembleBlocks(rawBlocks, tdmEdges, pdmZones);
            this.blockList = List.copyOf(blockMap.values());
            this.partList = List.copyOf(parts);

            log.info("Loaded {} blocks and {} parts from {}",
                    blockList.size(), partList.size(), filePath);

        } catch (LoaderValidationException e) {
            log.error("Excel data validation failed for {}:\n{}", filePath, e.getReport().format());
            throw e;
        } catch (IOException e) {
            report.addSheetLevel("(workbook)", "FILE_UNREADABLE",
                    "cannot read Excel file at '" + filePath + "': " + e.getMessage());
            log.error("Cannot open Excel file {}", filePath, e);
            throw new LoaderValidationException(report, e);
        }
    }

    @Override
    public List<Block> getAllBlocks() {
        return blockList;
    }

    @Override
    public Block getBlockById(String id) {
        return blockMap.get(id);
    }

    @Override
    public List<Part> getAllParts() {
        return partList;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Sheet lookup
    // ────────────────────────────────────────────────────────────────────────

    private static Sheet findSheet(Workbook workbook, String required, LoaderValidationReport report) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            String name = workbook.getSheetName(i);
            if (name != null && name.trim().equalsIgnoreCase(required)) {
                return workbook.getSheetAt(i);
            }
        }
        report.addSheetLevel(required, "MISSING_SHEET",
                "required sheet '" + required + "' not found in workbook (sheet name lookup is case-insensitive)");
        return null;
    }

    /** Lookup variant for optional sheets: returns {@code null} silently when absent. */
    private static Sheet findOptionalSheet(Workbook workbook, String name) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            String sheetName = workbook.getSheetName(i);
            if (sheetName != null && sheetName.trim().equalsIgnoreCase(name)) {
                return workbook.getSheetAt(i);
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Blocks sheet
    // ────────────────────────────────────────────────────────────────────────

    private static Map<String, RawBlock> parseBlocksSheet(Sheet sheet, LoaderValidationReport report) {
        Map<String, RawBlock> result = new LinkedHashMap<>();
        if (sheet == null) return result;

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            report.addSheetLevel(SHEET_BLOCKS, "MISSING_HEADER_ROW",
                    "first row is empty; expected the 13 required column headers");
            return result;
        }

        // Build header → column-index map (case-insensitive, trimmed)
        Map<String, Integer> headerIndex = new HashMap<>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            String name = ExcelCellReader.readString(headerRow.getCell(c));
            if (name == null) continue;
            headerIndex.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), c);
        }

        // Rule 3: every required header must be present
        Map<String, Integer> requiredCols = new LinkedHashMap<>();
        boolean missingAny = false;
        for (String required : REQUIRED_BLOCKS_HEADERS) {
            Integer idx = headerIndex.get(required.toLowerCase(Locale.ROOT));
            if (idx == null) {
                report.addSheetLevel(SHEET_BLOCKS, "BLOCKS_MISSING_HEADER",
                        "required column header '" + required + "' is missing from the Blocks sheet");
                missingAny = true;
            } else {
                requiredCols.put(required, idx);
            }
        }
        if (missingAny) {
            return result;
        }

        int idCol = requiredCols.get(COL_HL_BLOCK);
        int hrsCol = requiredCols.get(COL_HRS);
        int fteCol = requiredCols.get(COL_FTE);
        int toolsCol = requiredCols.get(COL_TOOLS);
        int toolsAmountCol = requiredCols.get(COL_TOOLS_AMOUNT);
        // Optional axis headers — missing axis = "no constraint on this axis for any block".
        Map<String, Integer> axisCols = new LinkedHashMap<>();
        for (String axis : POSITION_AXES) {
            Integer axisIdx = headerIndex.get(axis.toLowerCase(Locale.ROOT));
            if (axisIdx != null) axisCols.put(axis, axisIdx);
        }
        // Optional column — -1 means "not present; every block defaults to yellow"
        Integer colourIdx = headerIndex.get(COL_COLOUR.toLowerCase(Locale.ROOT));
        int colourCol = colourIdx == null ? -1 : colourIdx;

        Set<String> seenIds = new HashSet<>();

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            // Detect fully-empty row → skip silently (rule 12). Axis cells count:
            // a row with only axis data but no HL Block still triggers BLOCKS_MISSING_ID.
            List<Integer> knownCols = new ArrayList<>(requiredCols.values());
            knownCols.addAll(axisCols.values());
            if (isRowEmpty(row, knownCols)) continue;

            // ── HL Block (rules 4, 5) ──────────────────────────────────────
            String id = ExcelCellReader.readString(row.getCell(idCol));
            if (id == null) {
                // Mixed-empty row: at least one cell present but no ID
                report.add(SHEET_BLOCKS, r, idCol, "BLOCKS_MISSING_ID",
                        "row has data but '" + COL_HL_BLOCK + "' is empty");
                continue;
            }
            id = id.trim();
            if (!seenIds.add(id)) {
                report.add(SHEET_BLOCKS, r, idCol, "BLOCKS_DUPLICATE_ID",
                        "duplicate '" + COL_HL_BLOCK + "' value: '" + id + "'");
                continue;
            }

            // ── HRS (rules 6, 7) ───────────────────────────────────────────
            OptionalDouble hrsOpt = ExcelCellReader.readNumericOrEmpty(row.getCell(hrsCol));
            int durationHalfHours = -1;
            if (hrsOpt.isEmpty()) {
                report.add(SHEET_BLOCKS, r, hrsCol, "HRS_NOT_NUMERIC",
                        "'" + COL_HRS + "' must be a positive number; got empty or non-numeric value");
            } else {
                double hrs = hrsOpt.getAsDouble();
                if (hrs <= 0) {
                    report.add(SHEET_BLOCKS, r, hrsCol, "HRS_NOT_POSITIVE",
                            "'" + COL_HRS + "' must be > 0; got " + hrs);
                } else {
                    double doubled = hrs * 2.0;
                    if (Math.abs(doubled - Math.round(doubled)) > 1e-9) {
                        report.add(SHEET_BLOCKS, r, hrsCol, "HRS_NOT_HALF_HOUR",
                                "'" + COL_HRS + "' must be a whole or half hour (×2 must be integer); got " + hrs);
                    } else {
                        durationHalfHours = (int) Math.round(doubled);
                    }
                }
            }

            // ── FTE (rule 8) ───────────────────────────────────────────────
            OptionalDouble fteOpt = ExcelCellReader.readNumericOrEmpty(row.getCell(fteCol));
            int fteRequirement = -1;
            if (fteOpt.isEmpty()) {
                report.add(SHEET_BLOCKS, r, fteCol, "FTE_NOT_NUMERIC",
                        "'" + COL_FTE + "' must be a positive integer; got empty or non-numeric value");
            } else {
                double fte = fteOpt.getAsDouble();
                if (Math.abs(fte - Math.round(fte)) > 1e-9) {
                    report.add(SHEET_BLOCKS, r, fteCol, "FTE_NOT_INTEGER",
                            "'" + COL_FTE + "' must be an integer; got " + fte);
                } else if (fte < 1) {
                    report.add(SHEET_BLOCKS, r, fteCol, "FTE_NOT_POSITIVE",
                            "'" + COL_FTE + "' must be ≥ 1; got " + (long) fte);
                } else {
                    fteRequirement = (int) Math.round(fte);
                }
            }

            // ── TOOLS / TOOLS AMOUNT (rules 9, 10) ─────────────────────────
            // Tool acts as a constraint ONLY when TOOLS is non-empty AND
            // TOOLS AMOUNT, after trim and lowercase, equals "one". Every
            // other combination — tool without amount, amount without tool,
            // "Multiple", or any unrecognised amount — is treated as no
            // constraint, with no violation reported. Real datasets often
            // list a tool without specifying availability.
            String toolName = ExcelCellReader.readString(row.getCell(toolsCol));
            String toolsAmount = ExcelCellReader.readString(row.getCell(toolsAmountCol));
            ToolRequirement requiredTool = null;
            if (toolName != null && toolsAmount != null
                    && toolsAmount.trim().toLowerCase(Locale.ROOT).equals("one")) {
                requiredTool = new ToolRequirement(toolName.trim().toLowerCase(Locale.ROOT), true);
            }

            // ── 8 position axes (rule 11) ──────────────────────────────────
            // Only axes whose header is present participate; missing headers
            // leave that axis unconstrained for every block.
            Map<String, String> positionAxes = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : axisCols.entrySet()) {
                String axis = e.getKey();
                int col = e.getValue();
                Cell cell = row.getCell(col);
                if (cell == null || cell.getCellType() == CellType.BLANK) continue;
                if (cell.getCellType() != CellType.STRING) {
                    report.add(SHEET_BLOCKS, r, col, "POSITION_NOT_STRING",
                            "operational-position cell '" + axis + "' must be a string or empty; got cell type " + cell.getCellType());
                    continue;
                }
                String raw = cell.getStringCellValue().trim();
                if (raw.isEmpty()) continue;
                positionAxes.put(axis, raw.toLowerCase(Locale.ROOT));
            }

            // Only commit a RawBlock if everything on this row validated cleanly,
            // so cross-sheet checks downstream see consistent data.
            if (durationHalfHours > 0 && fteRequirement > 0) {
                String colour = readCellFillColour(colourCol < 0 ? null : row.getCell(colourCol));
                result.put(id, new RawBlock(id, id, durationHalfHours, fteRequirement,
                        Map.copyOf(positionAxes), requiredTool, colour));
            }
        }

        return result;
    }

    private static boolean isRowEmpty(Row row, Iterable<Integer> columnsToCheck) {
        for (Integer c : columnsToCheck) {
            if (ExcelCellReader.isPresent(row.getCell(c))) return false;
        }
        return true;
    }

    /**
     * Read the background-fill colour of a cell as a {@code "#RRGGBB"} hex
     * string. Returns {@link Block#DEFAULT_COLOUR} (yellow) whenever the cell
     * is absent, has no style, has no resolvable RGB fill (e.g. theme or
     * indexed colours without embedded RGB), or the workbook isn't an XSSF
     * workbook. Never throws — per product directive, colour is best-effort
     * and must never fail the load.
     */
    private static String readCellFillColour(Cell cell) {
        if (cell == null) return Block.DEFAULT_COLOUR;
        try {
            CellStyle style = cell.getCellStyle();
            if (!(style instanceof XSSFCellStyle xssf)) return Block.DEFAULT_COLOUR;
            XSSFColor fill = xssf.getFillForegroundColorColor();
            if (fill == null) return Block.DEFAULT_COLOUR;
            byte[] rgb = fill.getRGB();
            if (rgb == null || rgb.length < 3) return Block.DEFAULT_COLOUR;
            return String.format("#%02X%02X%02X",
                    rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
        } catch (RuntimeException e) {
            return Block.DEFAULT_COLOUR;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // TDM sheet (precedence matrix)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * @return map: successorId → list of predecessorIds (i.e. blocks that must
     *         finish before the successor starts).
     */
    private static Map<String, List<String>> parseTdmSheet(
            Sheet sheet, Set<String> knownBlockIds, LoaderValidationReport report) {

        Map<String, List<String>> predecessors = new HashMap<>();
        if (sheet == null) return predecessors;

        Row header = sheet.getRow(0);
        if (header == null) {
            report.addSheetLevel(SHEET_TDM, "MISSING_HEADER_ROW",
                    "first row is empty; expected mirrored block-name labels");
            return predecessors;
        }

        // Column labels: row 0 from col 1 onward
        List<String> colLabels = new ArrayList<>();
        for (int c = 1; c < header.getLastCellNum(); c++) {
            String name = ExcelCellReader.readString(header.getCell(c));
            colLabels.add(name == null ? null : name.trim());
        }
        // Trim trailing nulls
        while (!colLabels.isEmpty() && colLabels.get(colLabels.size() - 1) == null) {
            colLabels.remove(colLabels.size() - 1);
        }

        // Row labels: col 0 from row 1 onward
        List<String> rowLabels = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            String name = row == null ? null : ExcelCellReader.readString(row.getCell(0));
            rowLabels.add(name == null ? null : name.trim());
        }
        while (!rowLabels.isEmpty() && rowLabels.get(rowLabels.size() - 1) == null) {
            rowLabels.remove(rowLabels.size() - 1);
        }

        // Rule 13: same length
        if (rowLabels.size() != colLabels.size()) {
            report.addSheetLevel(SHEET_TDM, "TDM_NOT_SQUARE",
                    "TDM matrix is not square: " + rowLabels.size() + " row labels vs "
                            + colLabels.size() + " column labels");
            return predecessors;
        }

        // Rules 14, 15: labels mirrored, all present in Blocks
        boolean labelsValid = true;
        for (int i = 0; i < rowLabels.size(); i++) {
            String rowLabel = rowLabels.get(i);
            String colLabel = colLabels.get(i);
            if (rowLabel == null || rowLabel.isEmpty()) {
                report.add(SHEET_TDM, i + 1, 0, "TDM_EMPTY_ROW_LABEL",
                        "row label at position " + (i + 1) + " is empty");
                labelsValid = false;
                continue;
            }
            if (colLabel == null || colLabel.isEmpty()) {
                report.add(SHEET_TDM, 0, i + 1, "TDM_EMPTY_COL_LABEL",
                        "column label at position " + (i + 1) + " is empty");
                labelsValid = false;
                continue;
            }
            if (!rowLabel.equals(colLabel)) {
                report.add(SHEET_TDM, i + 1, 0, "TDM_LABEL_MISMATCH",
                        "row label '" + rowLabel + "' does not match column label '" + colLabel
                                + "' at position " + (i + 1) + "; row and column labels must mirror");
                labelsValid = false;
                continue;
            }
            if (!knownBlockIds.contains(rowLabel)) {
                report.add(SHEET_TDM, i + 1, 0, "TDM_UNKNOWN_BLOCK",
                        "block '" + rowLabel + "' referenced in TDM is not defined in the Blocks sheet");
                labelsValid = false;
            }
        }
        if (!labelsValid) {
            return predecessors;
        }

        // Walk the matrix interior. Convention: an X at (row i, col j) means
        // col j (the predecessor) must finish before row i (the successor)
        // — each row lists its predecessors in the columns.
        for (int i = 0; i < rowLabels.size(); i++) {
            Row row = sheet.getRow(i + 1);
            if (row == null) continue;
            String successorId = rowLabels.get(i);
            for (int j = 0; j < colLabels.size(); j++) {
                if (i == j) continue; // rule 17: skip diagonal
                Cell cell = row.getCell(j + 1);
                if (cell == null || cell.getCellType() == CellType.BLANK) continue;
                String value = ExcelCellReader.readString(cell);
                if (value == null) continue;
                String trimmed = value.trim();
                // Rule 16: only "X" (case-insensitive)
                if (!trimmed.equalsIgnoreCase("X")) {
                    report.add(SHEET_TDM, i + 1, j + 1, "TDM_INVALID_MARKER",
                            "TDM cell must be empty or 'X'; got '" + trimmed + "'");
                    continue;
                }
                String predecessorId = colLabels.get(j);
                predecessors.computeIfAbsent(successorId, k -> new ArrayList<>()).add(predecessorId);
            }
        }
        return predecessors;
    }

    // ────────────────────────────────────────────────────────────────────────
    // PDM sheet (spatial-zone matrix)
    // ────────────────────────────────────────────────────────────────────────

    private static Map<String, Set<String>> parsePdmSheet(
            Sheet sheet, Set<String> knownBlockIds, LoaderValidationReport report) {

        Map<String, Set<String>> result = new HashMap<>();
        if (sheet == null) return result;

        Row header = sheet.getRow(0);
        if (header == null) {
            report.addSheetLevel(SHEET_PDM, "MISSING_HEADER_ROW",
                    "first row is empty; expected zone-name column headers");
            return result;
        }

        // Column labels = zone names. Rule 20: non-empty + case-sensitively unique.
        List<String> zoneLabels = new ArrayList<>();
        Set<String> seenZones = new HashSet<>();
        for (int c = 1; c < header.getLastCellNum(); c++) {
            String name = ExcelCellReader.readString(header.getCell(c));
            if (name == null) {
                zoneLabels.add(null);
                continue;
            }
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                zoneLabels.add(null);
                continue;
            }
            if (!seenZones.add(trimmed)) {
                report.add(SHEET_PDM, 0, c, "PDM_DUPLICATE_ZONE",
                        "duplicate zone name '" + trimmed + "' (zone names are case-sensitive)");
            }
            zoneLabels.add(trimmed);
        }
        while (!zoneLabels.isEmpty() && zoneLabels.get(zoneLabels.size() - 1) == null) {
            zoneLabels.remove(zoneLabels.size() - 1);
        }
        // Empty internal zone labels are an error: a column with X marks but no name
        for (int j = 0; j < zoneLabels.size(); j++) {
            if (zoneLabels.get(j) == null) {
                report.add(SHEET_PDM, 0, j + 1, "PDM_EMPTY_ZONE_LABEL",
                        "zone label at column " + (j + 1) + " is empty but appears between non-empty labels");
            }
        }

        // Rule 19: row labels = block IDs, all present in Blocks
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String label = ExcelCellReader.readString(row.getCell(0));
            if (label == null) {
                // Allow fully-empty rows (no label and no marks); ignore.
                if (!isRowEmptyEntirely(row)) {
                    report.add(SHEET_PDM, r, 0, "PDM_MISSING_ROW_LABEL",
                            "row has data but the block-id cell is empty");
                }
                continue;
            }
            String blockId = label.trim();
            if (!knownBlockIds.contains(blockId)) {
                report.add(SHEET_PDM, r, 0, "PDM_UNKNOWN_BLOCK",
                        "block '" + blockId + "' referenced in PDM is not defined in the Blocks sheet");
                continue;
            }

            Set<String> occupied = new LinkedHashSet<>();
            for (int j = 0; j < zoneLabels.size(); j++) {
                Cell cell = row.getCell(j + 1);
                if (cell == null || cell.getCellType() == CellType.BLANK) continue;
                String value = ExcelCellReader.readString(cell);
                if (value == null) continue;
                String trimmed = value.trim();
                // Rule 21: only "X" (case-insensitive)
                if (!trimmed.equalsIgnoreCase("X")) {
                    report.add(SHEET_PDM, r, j + 1, "PDM_INVALID_MARKER",
                            "PDM cell must be empty or 'X'; got '" + trimmed + "'");
                    continue;
                }
                String zone = zoneLabels.get(j);
                if (zone != null) {
                    occupied.add(zone);
                }
            }
            // Rule 22: empty row (no zones occupied) is allowed
            result.put(blockId, occupied);
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Blocks_Parts sheet (optional; frontend grouping for batch selection)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Parse the optional {@code Blocks_Parts} sheet. Layout mirrors PDM:
     * column A holds block IDs, row 1 holds part names, an {@code X} at row
     * <i>i</i> / col <i>j</i> means block <i>i</i> belongs to part <i>j</i>.
     * Returns parts in column order. Each part's blockIds preserve the order
     * the blocks appear in the {@code Blocks} sheet (i.e., the iteration order
     * of {@code rawBlocks}).
     */
    private static List<Part> parseBlocksPartsSheet(
            Sheet sheet, Map<String, RawBlock> rawBlocks, LoaderValidationReport report) {

        Row header = sheet.getRow(0);
        if (header == null) {
            // An empty optional sheet is harmless — nothing to parse.
            return List.of();
        }

        // Column labels = part names. Non-empty + case-sensitively unique.
        List<String> partLabels = new ArrayList<>();
        Set<String> seenParts = new HashSet<>();
        for (int c = 1; c < header.getLastCellNum(); c++) {
            String name = ExcelCellReader.readString(header.getCell(c));
            if (name == null) {
                partLabels.add(null);
                continue;
            }
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                partLabels.add(null);
                continue;
            }
            if (!seenParts.add(trimmed)) {
                report.add(SHEET_BLOCKS_PARTS, 0, c, "PARTS_DUPLICATE_PART",
                        "duplicate part name '" + trimmed + "' (part names are case-sensitive)");
            }
            partLabels.add(trimmed);
        }
        while (!partLabels.isEmpty() && partLabels.get(partLabels.size() - 1) == null) {
            partLabels.remove(partLabels.size() - 1);
        }
        for (int j = 0; j < partLabels.size(); j++) {
            if (partLabels.get(j) == null) {
                report.add(SHEET_BLOCKS_PARTS, 0, j + 1, "PARTS_EMPTY_PART_LABEL",
                        "part label at column " + (j + 1) + " is empty but appears between non-empty labels");
            }
        }

        // Walk rows; for each (row, col) mark, append blockId to the matching part's list.
        // Use a LinkedHashMap keyed by part label so insertion order matches column order.
        Map<String, List<String>> partToBlockIds = new LinkedHashMap<>();
        for (String label : partLabels) {
            if (label != null) partToBlockIds.put(label, new ArrayList<>());
        }

        Set<String> knownBlockIds = rawBlocks.keySet();
        // Track membership per (part, block) to dedupe when a row appears twice.
        Map<String, Set<String>> seenMembers = new HashMap<>();

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String label = ExcelCellReader.readString(row.getCell(0));
            if (label == null) {
                if (!isRowEmptyEntirely(row)) {
                    report.add(SHEET_BLOCKS_PARTS, r, 0, "PARTS_MISSING_ROW_LABEL",
                            "row has data but the block-id cell is empty");
                }
                continue;
            }
            String blockId = label.trim();
            if (!knownBlockIds.contains(blockId)) {
                report.add(SHEET_BLOCKS_PARTS, r, 0, "PARTS_UNKNOWN_BLOCK",
                        "block '" + blockId + "' referenced in Blocks_Parts is not defined in the Blocks sheet");
                continue;
            }

            for (int j = 0; j < partLabels.size(); j++) {
                Cell cell = row.getCell(j + 1);
                if (cell == null || cell.getCellType() == CellType.BLANK) continue;
                String value = ExcelCellReader.readString(cell);
                if (value == null) continue;
                String trimmed = value.trim();
                if (!trimmed.equalsIgnoreCase("X")) {
                    report.add(SHEET_BLOCKS_PARTS, r, j + 1, "PARTS_INVALID_MARKER",
                            "Blocks_Parts cell must be empty or 'X'; got '" + trimmed + "'");
                    continue;
                }
                String part = partLabels.get(j);
                if (part == null) continue; // already reported as PARTS_EMPTY_PART_LABEL
                Set<String> members = seenMembers.computeIfAbsent(part, k -> new HashSet<>());
                if (members.add(blockId)) {
                    partToBlockIds.get(part).add(blockId);
                }
            }
        }

        // Return in column order, preserving block-row order inside each part.
        List<Part> result = new ArrayList<>(partToBlockIds.size());
        for (Map.Entry<String, List<String>> e : partToBlockIds.entrySet()) {
            result.add(new Part(e.getKey(), e.getValue()));
        }
        return result;
    }

    private static boolean isRowEmptyEntirely(Row row) {
        for (int c = 0; c < row.getLastCellNum(); c++) {
            if (ExcelCellReader.isPresent(row.getCell(c))) return false;
        }
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Cycle detection (rule 18)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Find one example cycle in the precedence graph using Kahn's algorithm
     * for detection followed by a DFS over the residual nodes for path
     * extraction. Returns {@code null} if the graph is acyclic.
     */
    private static List<String> findCycle(
            Set<String> nodes, Map<String, List<String>> predecessorMap) {

        // Build forward adjacency: pred → successors
        Map<String, List<String>> succ = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String n : nodes) {
            succ.put(n, new ArrayList<>());
            inDegree.put(n, 0);
        }
        for (Map.Entry<String, List<String>> e : predecessorMap.entrySet()) {
            String successor = e.getKey();
            for (String predecessor : e.getValue()) {
                succ.get(predecessor).add(successor);
                inDegree.merge(successor, 1, Integer::sum);
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        Set<String> removed = new HashSet<>();
        while (!queue.isEmpty()) {
            String n = queue.removeFirst();
            removed.add(n);
            for (String s : succ.get(n)) {
                int d = inDegree.get(s) - 1;
                inDegree.put(s, d);
                if (d == 0) queue.add(s);
            }
        }
        if (removed.size() == nodes.size()) {
            return null; // acyclic
        }

        // Residual nodes contain at least one cycle. DFS from any residual node
        // to find one example.
        Set<String> residual = new HashSet<>(nodes);
        residual.removeAll(removed);
        String start = residual.iterator().next();
        return dfsForCycle(start, succ, residual, new LinkedHashSet<>());
    }

    private static List<String> dfsForCycle(
            String node, Map<String, List<String>> succ, Set<String> residual,
            LinkedHashSet<String> onStack) {
        if (onStack.contains(node)) {
            List<String> stackList = new ArrayList<>(onStack);
            int idx = stackList.indexOf(node);
            List<String> cycle = new ArrayList<>(stackList.subList(idx, stackList.size()));
            cycle.add(node); // close the loop
            return cycle;
        }
        onStack.add(node);
        for (String next : succ.getOrDefault(node, List.of())) {
            if (!residual.contains(next)) continue;
            List<String> found = dfsForCycle(next, succ, residual, onStack);
            if (found != null) return found;
        }
        onStack.remove(node);
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Final assembly
    // ────────────────────────────────────────────────────────────────────────

    private static Map<String, Block> assembleBlocks(
            Map<String, RawBlock> rawBlocks,
            Map<String, List<String>> predecessors,
            Map<String, Set<String>> pdmZones) {

        Map<String, Block> assembled = new LinkedHashMap<>();
        for (RawBlock raw : rawBlocks.values()) {
            List<String> preds = predecessors.getOrDefault(raw.id, List.of());
            Set<String> zones = pdmZones.getOrDefault(raw.id, Set.of());
            assembled.put(raw.id, new Block(
                    raw.id,
                    raw.name,
                    raw.durationHalfHours,
                    raw.fteRequirement,
                    zones,
                    raw.positionAxes,
                    raw.requiredTool,
                    preds,
                    raw.colour
            ));
        }
        return assembled;
    }

    private record RawBlock(
            String id,
            String name,
            int durationHalfHours,
            int fteRequirement,
            Map<String, String> positionAxes,
            ToolRequirement requiredTool,
            String colour
    ) {}
}
