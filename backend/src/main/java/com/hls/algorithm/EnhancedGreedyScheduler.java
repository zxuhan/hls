package com.hls.algorithm;

import com.hls.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Candidate C — Domain-enhanced greedy.
 *
 * <p>Composite priority score, convex combination of two normalised signals:
 * <pre>
 *   score(i) = (1 − w) · CPR_norm(i) + w · OPA(i)
 * </pre>
 * where {@code w ∈ {0, 0.25, 0.5, 0.75, 1.0}} (default 0.5).
 *
 * <ul>
 *   <li><b>CPR (Critical Path Remaining)</b> — longest remaining duration from
 *       this block to any terminal node. Normalised to [0, 1] by dividing by
 *       the global max CPR.</li>
 *   <li><b>OPA (Operational Position Affinity)</b> — continuous measure of how
 *       well this block preserves the machine's current orientation. For each
 *       axis the block declares a value on, the axis's "current value" is
 *       defined as the value of the most recently started already-placed block
 *       that declares the axis; a match (or an axis no-one has touched yet)
 *       counts as 1, a mismatch as 0. OPA is the mean across the block's
 *       declared axes. Orientation-neutral blocks score 1.</li>
 * </ul>
 *
 * <p>Earlier versions used a binary OPA evaluated against currently-running
 * blocks; that was redundant with {@link GreedyPlacementEngine#findEarliestValidStart}
 * (which rules out axis conflicts at the returned start time) so OPA was
 * effectively always 1 and {@code w} had no effect on the output. The
 * look-back continuous definition above gives {@code w} a genuine tuning role.
 */
public class EnhancedGreedyScheduler implements Scheduler {

    @Override
    public ScheduleResult schedule(List<Block> blocks, List<ShiftDay> shiftSchedule, Map<String, Object> params) {
        long startMs = System.currentTimeMillis();

        double weight = ((Number) params.getOrDefault("candidateCWeight", 0.5)).doubleValue();

        if (PrecedenceHelper.hasCycle(blocks)) {
            return new ScheduleResult(false, "Precedence cycle detected", 0,
                    List.of(), System.currentTimeMillis() - startMs, null, null);
        }

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

        // Per-axis "machine current state": (latest startTime, value) of any placed
        // block that has declared this axis. Updated inside the main loop.
        Map<String, String> currentAxisValue = new HashMap<>();
        Map<String, Integer> currentAxisLatestStart = new HashMap<>();

        while (!remaining.isEmpty()) {
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

            Block bestBlock = null;
            double bestScore = -1;
            int bestStartTime = -1;

            for (Block block : schedulable) {
                int minStart = engine.getEarliestPredecessorEnd(block);
                int startTime = engine.findEarliestValidStart(block, minStart);
                if (startTime < 0) continue;

                double cprScore = cprNorm.getOrDefault(block.id(), 0.0);
                double opaScore = computeLookBackOpa(block, currentAxisValue);
                double score = (1.0 - weight) * cprScore + weight * opaScore;

                if (score > bestScore
                        || (score == bestScore && (bestBlock == null || block.id().compareTo(bestBlock.id()) < 0))) {
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
            // Update machine orientation state: for every axis this block declares,
            // record the value iff this is the most recent start we've seen for the axis.
            for (var e : bestBlock.positionAxes().entrySet()) {
                String axis = e.getKey();
                Integer latest = currentAxisLatestStart.get(axis);
                if (latest == null || latest < bestStartTime) {
                    currentAxisLatestStart.put(axis, bestStartTime);
                    currentAxisValue.put(axis, e.getValue());
                }
            }
            remaining.remove(bestBlock.id());
        }

        List<ScheduledBlock> result = new ArrayList<>(engine.getScheduled().values());
        int makespan = result.stream().mapToInt(ScheduledBlock::dayIndex).max().orElse(0);
        long runtimeMs = System.currentTimeMillis() - startMs;

        return new ScheduleResult(true, null, makespan, result, runtimeMs, null, null);
    }

    /**
     * Continuous OPA in [0, 1]: fraction of the block's declared axes whose
     * value equals the current machine value on that axis (or for which no
     * placed block has set the axis yet). Orientation-neutral blocks (no
     * axes declared) score 1.
     */
    private static double computeLookBackOpa(Block block, Map<String, String> currentAxisValue) {
        Map<String, String> blockAxes = block.positionAxes();
        if (blockAxes.isEmpty()) return 1.0;
        int matches = 0;
        int total = 0;
        for (var e : blockAxes.entrySet()) {
            total++;
            String current = currentAxisValue.get(e.getKey());
            if (current == null || current.equals(e.getValue())) {
                matches++;
            }
        }
        return total == 0 ? 1.0 : (double) matches / total;
    }
}
