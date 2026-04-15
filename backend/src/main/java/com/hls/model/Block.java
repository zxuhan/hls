package com.hls.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable description of an HL Block as loaded from the Excel workbook.
 *
 * <p>Field shapes match the real Excel contract:
 * <ul>
 *   <li>{@code occupiedZones} — set of PDM zone names this block holds for its
 *       entire duration. Zone names are case-sensitive and discovered per file.
 *       Empty set means the block has no spatial-exclusivity constraint.</li>
 *   <li>{@code positionAxes} — map keyed by operational-position axis name
 *       ({@code "WS"}, {@code "(-)WS"}, ..., {@code "RH Library"}). Only present
 *       (non-empty) axes appear in the map. Values are stored normalized
 *       (trim + lowercase) so per-axis equality is case-insensitive. An empty
 *       map means the block is orientation-neutral on every axis.</li>
 *   <li>{@code requiredTool} — at most one tool per block ({@code null} when no
 *       tool is needed). The {@code exclusive} flag is true for {@code TOOLS
 *       AMOUNT = "One"}, false for {@code "Multiple"}; only exclusive tools
 *       generate scheduling constraints.</li>
 *   <li>{@code predecessorBlockIds} — block IDs that must finish before this
 *       block may start, derived from the TDM matrix.</li>
 * </ul>
 */
public record Block(
    String id,
    String name,
    int durationHalfHours,
    int fteRequirement,
    Set<String> occupiedZones,
    Map<String, String> positionAxes,
    ToolRequirement requiredTool,
    List<String> predecessorBlockIds,
    String colour
) {
    public static final String DEFAULT_COLOUR = "#FFFF00";

    public Block {
        occupiedZones = occupiedZones == null ? Set.of() : Set.copyOf(occupiedZones);
        positionAxes = positionAxes == null ? Map.of() : Map.copyOf(positionAxes);
        predecessorBlockIds = predecessorBlockIds == null ? List.of() : List.copyOf(predecessorBlockIds);
        colour = (colour == null || colour.isBlank()) ? DEFAULT_COLOUR : colour;
    }
}
