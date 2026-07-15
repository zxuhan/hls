package com.hls.algorithm;

import com.hls.model.Block;
import com.hls.model.OdmConstraints;
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

    /** Every placed block interval {@code [start, end)} — backs the
     * Parallelism exclusivity check for {@code Parallelism = No} blocks. */
    private final List<int[]> allOccupancy = new ArrayList<>();

    /** Intervals {@code [start, end)} of placed {@code Parallelism = No}
     * blocks. No block — exclusive or not — may overlap one of these. */
    private final List<int[]> exclusiveOccupancy = new ArrayList<>();

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
        int dur = block.durationHalfHours();
        OdmConstraints odm = block.odm();
        int t = Math.max(minStart, 0);
        // Calendar-day pin: never start before the pinned day begins.
        if (odm.hasDayPin()) {
            t = Math.max(t, (odm.pinnedDay() - 1) * TimelineHelper.SLOTS_PER_DAY);
        }

        while (t + dur <= horizonLength) {
            // Calendar-day pin: once we are past the pinned day, give up.
            if (odm.hasDayPin() && TimelineHelper.dayIndex(t) > odm.pinnedDay()) {
                return -1;
            }
            // Hour pin: skip ahead to the next slot whose intra-day offset matches.
            if (odm.hasHourPin()) {
                int target = (odm.pinnedStartHour() - 1) * 2;
                int off = t % TimelineHelper.SLOTS_PER_DAY;
                if (off != target) {
                    t += (off < target) ? (target - off)
                                        : (TimelineHelper.SLOTS_PER_DAY - off + target);
                    continue;
                }
            }
            // Day containment: jump to the next day if the block would straddle midnight.
            if (!TimelineHelper.fitsInSingleDay(t, dur)) {
                t = TimelineHelper.nextDayStart(t);
                continue;
            }
            // All remaining hard constraints (FTE, zones, tools, position, parallelism).
            if (canPlaceAt(block, t)) {
                return t;
            }
            t++;
        }

        return -1; // No valid placement found
    }

    /**
     * Whether {@code block} can occupy {@code [t, t+duration)} against every
     * hard constraint the engine tracks: calendar/hour pins, day containment,
     * FTE capacity, spatial zones, tool exclusivity, operational position, and
     * parallelism exclusivity. Used both for the single-block scan above and
     * for laying out a contiguous sequence-group chain.
     */
    private boolean canPlaceAt(Block block, int t) {
        int dur = block.durationHalfHours();
        int end = t + dur;
        if (t < 0 || end > horizonLength) return false;
        if (!isStartAdmissibleForPin(block, t)) return false;
        if (!TimelineHelper.fitsInSingleDay(t, dur)) return false;
        if (!TimelineHelper.hasSufficientFte(t, dur, block.fteRequirement(), fteCapacity, fteUsed)) return false;
        if (hasZoneConflict(block, t, end)) return false;
        if (hasToolConflict(block, t, end)) return false;
        if (hasPositionConflict(block, t, end)) return false;
        if (hasParallelConflict(block, t, end)) return false;
        return true;
    }

    private static boolean isStartAdmissibleForPin(Block block, int t) {
        OdmConstraints odm = block.odm();
        if (odm.hasDayPin() && TimelineHelper.dayIndex(t) != odm.pinnedDay()) {
            return false;
        }
        if (odm.hasHourPin()
                && (t % TimelineHelper.SLOTS_PER_DAY) != (odm.pinnedStartHour() - 1) * 2) {
            return false;
        }
        return true;
    }

    /**
     * Earliest absolute start at which the whole sequence-group chain fits when
     * its members are laid back-to-back in the given order, or {@code -1} if no
     * such start exists. The chain occupies a single contiguous interval of
     * length equal to the sum of member durations; other blocks may run in
     * parallel during it.
     */
    public int findEarliestContiguousGroupStart(List<Block> orderedMembers, int minStart) {
        int total = 0;
        for (Block m : orderedMembers) {
            total += m.durationHalfHours();
        }
        int t = Math.max(minStart, 0);
        while (t + total <= horizonLength) {
            if (canPlaceGroupAt(orderedMembers, t)) {
                return t;
            }
            t++;
        }
        return -1;
    }

    private boolean canPlaceGroupAt(List<Block> orderedMembers, int startT) {
        // Members occupy disjoint, back-to-back sub-intervals, so checking each
        // against the current engine state (which excludes its siblings) is
        // sufficient — siblings never overlap one another.
        int offset = 0;
        for (Block m : orderedMembers) {
            if (!canPlaceAt(m, startT + offset)) {
                return false;
            }
            offset += m.durationHalfHours();
        }
        return true;
    }

    /** Place every member of a sequence-group chain back-to-back from {@code startT}. */
    public void placeGroupContiguous(List<Block> orderedMembers, int startT) {
        int offset = 0;
        for (Block m : orderedMembers) {
            placeBlock(m, startT + offset);
            offset += m.durationHalfHours();
        }
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

        // Update parallelism bookkeeping (constraint 9).
        allOccupancy.add(new int[]{startTime, endTime});
        if (block.odm().noParallel()) {
            exclusiveOccupancy.add(new int[]{startTime, endTime});
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

    /**
     * Parallelism exclusivity (constraint 9). A {@code Parallelism = No} block
     * may not overlap anything already placed; conversely, no block may overlap
     * an already-placed {@code Parallelism = No} block.
     */
    private boolean hasParallelConflict(Block block, int start, int end) {
        if (block.odm().noParallel() && hasOverlap(allOccupancy, start, end)) {
            return true;
        }
        return hasOverlap(exclusiveOccupancy, start, end);
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
