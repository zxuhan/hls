package com.hls.algorithm;

import com.hls.model.*;

import java.util.*;
import java.util.stream.Collectors;

public class GreedyScheduler implements Scheduler {

    @Override
    public ScheduleResult schedule(List<Block> blocks, List<ShiftDay> shiftSchedule, Map<String, Object> params) {
        long startMs = System.currentTimeMillis();

        String priorityRule = (String) params.getOrDefault("priorityRule", "MTS");

        // Validate: check for cycles
        if (PrecedenceHelper.hasCycle(blocks)) {
            return new ScheduleResult(false, "Precedence cycle detected", 0,
                    List.of(), System.currentTimeMillis() - startMs, null, null);
        }

        // Compute priority values
        Map<String, Integer> priorityValues;
        boolean ascending;
        if ("SPT".equals(priorityRule)) {
            priorityValues = blocks.stream()
                    .collect(Collectors.toMap(Block::id, Block::durationHalfHours));
            ascending = true; // shorter duration = higher priority
        } else {
            // MTS
            priorityValues = PrecedenceHelper.computeTotalSuccessors(blocks);
            ascending = false; // more successors = higher priority
        }

        Set<String> blockIds = blocks.stream().map(Block::id).collect(Collectors.toSet());
        Map<String, Block> blockMap = blocks.stream()
                .collect(Collectors.toMap(Block::id, b -> b));

        GreedyPlacementEngine engine = new GreedyPlacementEngine(shiftSchedule);
        SequenceGroupPlanner planner = new SequenceGroupPlanner(blocks);
        Set<String> remaining = new LinkedHashSet<>(blockIds);

        while (!remaining.isEmpty()) {
            // Schedulable ungrouped singles: all in-run predecessors placed.
            // Grouped blocks are placed only via their group (handled below).
            List<Block> schedulable = new ArrayList<>();
            for (String id : remaining) {
                if (planner.isGrouped(id)) continue;
                Block block = blockMap.get(id);
                boolean allPredsScheduled = block.predecessorBlockIds().stream()
                        .filter(blockIds::contains)
                        .allMatch(pred -> engine.getScheduled().containsKey(pred));
                if (allPredsScheduled) {
                    schedulable.add(block);
                }
            }

            // Sort by priority (stable — preserves the original tie-break order).
            if (ascending) {
                schedulable.sort(Comparator.comparingInt(b -> priorityValues.getOrDefault(b.id(), 0)));
            } else {
                schedulable.sort((a, b) -> Integer.compare(
                        priorityValues.getOrDefault(b.id(), 0),
                        priorityValues.getOrDefault(a.id(), 0)));
            }
            Block topSingle = schedulable.isEmpty() ? null : schedulable.get(0);

            // Ready sequence groups, represented by their best-priority member.
            String bestGroup = null;
            int bestGroupKey = 0;
            for (String g : planner.groupIds()) {
                List<Block> members = planner.members(g);
                if (members.stream().noneMatch(m -> remaining.contains(m.id()))) continue;
                if (!planner.isReady(g, pred -> engine.getScheduled().containsKey(pred))) continue;
                int key = groupPriority(members, priorityValues, ascending);
                if (bestGroup == null || strictlyBetter(key, bestGroupKey, ascending)
                        || (key == bestGroupKey && g.compareTo(bestGroup) < 0)) {
                    bestGroup = g;
                    bestGroupKey = key;
                }
            }

            if (topSingle == null && bestGroup == null) {
                return new ScheduleResult(false,
                        "No schedulable blocks found — possible missing predecessor outside block set "
                                + "or a sequence group blocked by a cyclic external dependency",
                        0, List.of(), System.currentTimeMillis() - startMs, null, null);
            }

            // Choose single vs group. With no groups this always picks the single,
            // reproducing the original baseline exactly.
            boolean placeGroup;
            if (topSingle == null) {
                placeGroup = true;
            } else if (bestGroup == null) {
                placeGroup = false;
            } else {
                int singleKey = priorityValues.getOrDefault(topSingle.id(), 0);
                placeGroup = strictlyBetter(bestGroupKey, singleKey, ascending);
            }

            if (!placeGroup) {
                int minStart = engine.getEarliestPredecessorEnd(topSingle);
                int startTime = engine.findEarliestValidStart(topSingle, minStart);
                if (startTime < 0) {
                    return new ScheduleResult(false,
                            "No feasible schedule found: block " + topSingle.id() +
                                    " (duration " + topSingle.durationHalfHours() +
                                    " half-hours) cannot fit in any available window",
                            0, List.of(), System.currentTimeMillis() - startMs, null, null);
                }
                engine.placeBlock(topSingle, startTime);
                remaining.remove(topSingle.id());
            } else {
                List<Block> members = planner.members(bestGroup);
                int minStart = 0;
                for (Block m : members) {
                    minStart = Math.max(minStart, engine.getEarliestPredecessorEnd(m));
                }
                int startTime = engine.findEarliestContiguousGroupStart(members, minStart);
                if (startTime < 0) {
                    return new ScheduleResult(false,
                            "No feasible schedule found: sequence group '" + bestGroup +
                                    "' cannot be placed as one contiguous chain in any available window",
                            0, List.of(), System.currentTimeMillis() - startMs, null, null);
                }
                engine.placeGroupContiguous(members, startTime);
                for (Block m : members) {
                    remaining.remove(m.id());
                }
            }
        }

        List<ScheduledBlock> result = new ArrayList<>(engine.getScheduled().values());
        int makespan = result.stream().mapToInt(ScheduledBlock::dayIndex).max().orElse(0);
        long runtimeMs = System.currentTimeMillis() - startMs;

        return new ScheduleResult(true, null, makespan, result, runtimeMs, null, null);
    }

    /** Representative priority for a group: the best member value under the
     * active rule (max successors for MTS, min duration for SPT). */
    private static int groupPriority(List<Block> members, Map<String, Integer> priorityValues, boolean ascending) {
        int best = ascending ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        for (Block m : members) {
            int v = priorityValues.getOrDefault(m.id(), 0);
            best = ascending ? Math.min(best, v) : Math.max(best, v);
        }
        return best;
    }

    /** Whether priority {@code a} is strictly better than {@code b} under the rule. */
    private static boolean strictlyBetter(int a, int b, boolean ascending) {
        return ascending ? a < b : a > b;
    }
}
