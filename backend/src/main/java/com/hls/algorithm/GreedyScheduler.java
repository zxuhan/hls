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
        Set<String> remaining = new LinkedHashSet<>(blockIds);

        while (!remaining.isEmpty()) {
            // Find schedulable blocks: all predecessors already scheduled
            List<Block> schedulable = new ArrayList<>();
            for (String id : remaining) {
                Block block = blockMap.get(id);
                boolean allPredsScheduled = block.predecessorBlockIds().stream()
                        .filter(blockIds::contains)
                        .allMatch(pred -> engine.getScheduled().containsKey(pred));
                if (allPredsScheduled) {
                    schedulable.add(block);
                }
            }

            if (schedulable.isEmpty()) {
                return new ScheduleResult(false,
                        "No schedulable blocks found — possible missing predecessor outside block set",
                        0, List.of(), System.currentTimeMillis() - startMs, null, null);
            }

            // Sort by priority
            if (ascending) {
                schedulable.sort(Comparator.comparingInt(b -> priorityValues.getOrDefault(b.id(), 0)));
            } else {
                schedulable.sort((a, b) -> Integer.compare(
                        priorityValues.getOrDefault(b.id(), 0),
                        priorityValues.getOrDefault(a.id(), 0)));
            }

            // Place the highest-priority block
            Block selected = schedulable.get(0);
            int minStart = engine.getEarliestPredecessorEnd(selected);
            int startTime = engine.findEarliestValidStart(selected, minStart);

            if (startTime < 0) {
                return new ScheduleResult(false,
                        "No feasible schedule found: block " + selected.id() +
                                " (duration " + selected.durationHalfHours() +
                                " half-hours) cannot fit in any available window",
                        0, List.of(), System.currentTimeMillis() - startMs, null, null);
            }

            engine.placeBlock(selected, startTime);
            remaining.remove(selected.id());
        }

        List<ScheduledBlock> result = new ArrayList<>(engine.getScheduled().values());
        int makespan = result.stream().mapToInt(ScheduledBlock::dayIndex).max().orElse(0);
        long runtimeMs = System.currentTimeMillis() - startMs;

        return new ScheduleResult(true, null, makespan, result, runtimeMs, null, null);
    }
}
