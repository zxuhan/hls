package com.hls.algorithm;

import com.hls.model.Block;
import com.hls.model.ScheduledBlock;
import com.hls.model.ShiftDay;
import com.hls.model.ToolRequirement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains the partial schedule for the greedy constructors and answers
 * "earliest valid start time" / placement queries against the six hard
 * constraints (day containment, FTE capacity, spatial exclusivity, tool
 * exclusivity, precedence, multi-axis operational position).
 */
public class GreedyPlacementEngine {

    private final int[] fteCapacity;
    private final int[] fteUsed;
    private final int horizonLength;
    private final Map<String, ScheduledBlock> scheduled = new LinkedHashMap<>();

    /** Zone name → list of {@code [start, end)} intervals occupied by some block. */
    private final Map<String, List<int[]>> zoneOccupancy = new HashMap<>();

    /** Exclusive tool name → list of {@code [start, end)} intervals. */
    private final Map<String, List<int[]>> toolOccupancy = new HashMap<>();

    /**
     * Per-axis occupancy: axis name → list of {@code [start, end, valueId]}.
     * {@code valueId} is an index into {@link #axisValueLookup}{@code .get(axis)}
     * — used so the inner conflict loop is just an integer compare, with a
     * string-equality fallback in case of hash collisions.
     */
    private final Map<String, List<int[]>> axisOccupancy = new HashMap<>();
    private final Map<String, List<String>> axisValueLookup = new HashMap<>();

    public GreedyPlacementEngine(List<ShiftDay> shiftSchedule) {
        this.fteCapacity = TimelineHelper.buildFteCapacityArray(shiftSchedule);
        this.horizonLength = shiftSchedule.size() * TimelineHelper.SLOTS_PER_DAY;
        this.fteUsed = new int[horizonLength];
    }

    public Map<String, ScheduledBlock> getScheduled() {
        return Collections.unmodifiableMap(scheduled);
    }

    public int getHorizonLength() {
        return horizonLength;
    }

    public int findEarliestValidStart(Block block, int minStart) {
        int t = Math.max(minStart, 0);

        while (t + block.durationHalfHours() <= horizonLength) {
            // Day containment check
            if (!TimelineHelper.fitsInSingleDay(t, block.durationHalfHours())) {
                t = TimelineHelper.nextDayStart(t);
                continue;
            }

            // FTE capacity check
            if (!TimelineHelper.hasSufficientFte(t, block.durationHalfHours(),
                    block.fteRequirement(), fteCapacity, fteUsed)) {
                t++;
                continue;
            }

            // Spatial exclusivity check (set intersection over occupied zones)
            if (hasZoneConflict(block, t, t + block.durationHalfHours())) {
                t++;
                continue;
            }

            // Tool exclusivity check
            if (hasToolConflict(block, t, t + block.durationHalfHours())) {
                t++;
                continue;
            }

            // Operational position check (per-axis thesis rule)
            if (hasPositionConflict(block, t, t + block.durationHalfHours())) {
                t++;
                continue;
            }

            return t;
        }

        return -1; // No valid placement found
    }

    public void placeBlock(Block block, int startTime) {
        int endTime = startTime + block.durationHalfHours();
        int dayIndex = TimelineHelper.dayIndex(startTime);

        // Update FTE usage
        for (int t = startTime; t < endTime; t++) {
            fteUsed[t] += block.fteRequirement();
        }

        // Update zone occupancy
        for (String zone : block.occupiedZones()) {
            zoneOccupancy.computeIfAbsent(zone, k -> new ArrayList<>())
                    .add(new int[]{startTime, endTime});
        }

        // Update tool occupancy
        ToolRequirement tool = block.requiredTool();
        if (tool != null && tool.exclusive()) {
            toolOccupancy.computeIfAbsent(tool.toolName(), k -> new ArrayList<>())
                    .add(new int[]{startTime, endTime});
        }

        // Update per-axis occupancy
        for (Map.Entry<String, String> e : block.positionAxes().entrySet()) {
            String axis = e.getKey();
            String value = e.getValue();
            int valueId = internAxisValue(axis, value);
            axisOccupancy.computeIfAbsent(axis, k -> new ArrayList<>())
                    .add(new int[]{startTime, endTime, valueId});
        }

        scheduled.put(block.id(), new ScheduledBlock(block.id(), startTime, endTime, dayIndex));
    }

    /**
     * For the EnhancedGreedyScheduler's OPA score: returns true iff for every
     * axis the block declares, every block currently running at {@code time}
     * either is silent on that axis or carries the same value.
     */
    public boolean matchesCurrentAxes(Block block, int time) {
        if (block.positionAxes().isEmpty()) return true;
        for (Map.Entry<String, String> e : block.positionAxes().entrySet()) {
            String axis = e.getKey();
            int blockValueId = lookupAxisValueId(axis, e.getValue());
            List<int[]> occ = axisOccupancy.get(axis);
            if (occ == null) continue;
            for (int[] interval : occ) {
                if (interval[0] <= time && time < interval[1]) {
                    if (interval[2] != blockValueId) {
                        // Different non-empty value on the same axis at this instant
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public int getEarliestPredecessorEnd(Block block) {
        int minStart = 0;
        for (String predId : block.predecessorBlockIds()) {
            ScheduledBlock pred = scheduled.get(predId);
            if (pred != null) {
                minStart = Math.max(minStart, pred.endTime());
            }
        }
        return minStart;
    }

    private boolean hasOverlap(List<int[]> intervals, int start, int end) {
        if (intervals == null) return false;
        for (int[] interval : intervals) {
            if (start < interval[1] && interval[0] < end) {
                return true;
            }
        }
        return false;
    }

    private boolean hasZoneConflict(Block block, int start, int end) {
        for (String zone : block.occupiedZones()) {
            if (hasOverlap(zoneOccupancy.get(zone), start, end)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasToolConflict(Block block, int start, int end) {
        ToolRequirement tool = block.requiredTool();
        if (tool == null || !tool.exclusive()) return false;
        return hasOverlap(toolOccupancy.get(tool.toolName()), start, end);
    }

    private boolean hasPositionConflict(Block block, int start, int end) {
        if (block.positionAxes().isEmpty()) return false;
        for (Map.Entry<String, String> e : block.positionAxes().entrySet()) {
            String axis = e.getKey();
            int blockValueId = internAxisValue(axis, e.getValue());
            List<int[]> occ = axisOccupancy.get(axis);
            if (occ == null) continue;
            for (int[] interval : occ) {
                if (start < interval[1] && interval[0] < end) {
                    if (interval[2] != blockValueId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Intern an (axis, value) pair into a stable integer id used for fast comparison. */
    private int internAxisValue(String axis, String value) {
        List<String> values = axisValueLookup.computeIfAbsent(axis, k -> new ArrayList<>());
        int idx = values.indexOf(value);
        if (idx >= 0) return idx;
        values.add(value);
        return values.size() - 1;
    }

    private int lookupAxisValueId(String axis, String value) {
        List<String> values = axisValueLookup.get(axis);
        if (values == null) return -1;
        return values.indexOf(value);
    }
}
