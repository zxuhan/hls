package com.hls.algorithm;

import com.hls.model.*;

import java.util.*;
import java.util.stream.Collectors;

public class EnhancedGreedyScheduler implements Scheduler {

    @Override
    public ScheduleResult schedule(List<Block> blocks, List<ShiftDay> shiftSchedule, Map<String, Object> params) {
        long startMs = System.currentTimeMillis();

        double weight = ((Number) params.getOrDefault("candidateCWeight", 0.5)).doubleValue();

        // Validate: check for cycles
        if (PrecedenceHelper.hasCycle(blocks)) {
            return new ScheduleResult(false, "Precedence cycle detected", 0,
                    List.of(), System.currentTimeMillis() - startMs, null, null);
        }

        // Precompute CPR and normalize
        Map<String, Integer> cpr = PrecedenceHelper.computeCriticalPathRemaining(blocks);
        int maxCpr = cpr.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        Map<String, Double> cprNorm = new HashMap<>();
        for (var entry : cpr.entrySet()) {
            cprNorm.put(entry.getKey(), (double) entry.getValue() / maxCpr);
        }

        Set<String> blockIds = blocks.stream().map(Block::id).collect(Collectors.toSet());
        Map<String, Block> blockMap = blocks.stream()
                .collect(Collectors.toMap(Block::id, b -> b));

        GreedyPlacementEngine engine = new GreedyPlacementEngine(shiftSchedule);
        Set<String> remaining = new LinkedHashSet<>(blockIds);

        while (!remaining.isEmpty()) {
            // Find schedulable blocks
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

            // Compute scores: CPR_norm + w * OPA
            Block bestBlock = null;
            double bestScore = -1;
            int bestStartTime = -1;

            for (Block block : schedulable) {
                int minStart = engine.getEarliestPredecessorEnd(block);
                int startTime = engine.findEarliestValidStart(block, minStart);
                if (startTime < 0) continue;

                double cprScore = cprNorm.getOrDefault(block.id(), 0.0);

                // OPA: 1 iff for every axis the block declares, no currently-running
                // block at startTime carries a conflicting non-empty value on that axis.
                // Position-neutral blocks (empty axis map) trivially match.
                double opa = engine.matchesCurrentAxes(block, startTime) ? 1.0 : 0.0;

                double score = cprScore + weight * opa;

                if (score > bestScore || (score == bestScore && (bestBlock == null || block.id().compareTo(bestBlock.id()) < 0))) {
                    bestScore = score;
                    bestBlock = block;
                    bestStartTime = startTime;
                }
            }

            if (bestBlock == null) {
                return new ScheduleResult(false,
                        "No feasible schedule found: remaining blocks cannot fit in any available window",
                        0, List.of(), System.currentTimeMillis() - startMs, null, null);
            }

            engine.placeBlock(bestBlock, bestStartTime);
            remaining.remove(bestBlock.id());
        }

        List<ScheduledBlock> result = new ArrayList<>(engine.getScheduled().values());
        int makespan = result.stream().mapToInt(ScheduledBlock::dayIndex).max().orElse(0);
        long runtimeMs = System.currentTimeMillis() - startMs;

        return new ScheduleResult(true, null, makespan, result, runtimeMs, null, null);
    }
}
