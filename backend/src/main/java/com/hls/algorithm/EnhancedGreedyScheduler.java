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
        SequenceGroupPlanner planner = new SequenceGroupPlanner(blocks);
        Set<String> remaining = new LinkedHashSet<>(blockIds);

        // Per-axis "machine current state": (latest startTime, value) of any placed
        // block that has declared this axis. Updated inside the main loop.
        Map<String, String> currentAxisValue = new HashMap<>();
        Map<String, Integer> currentAxisLatestStart = new HashMap<>();

        while (!remaining.isEmpty()) {
            // Best ungrouped single by composite score (same logic and id tie-break
            // as the original baseline; grouped blocks are placed via their group).
            Block bestBlock = null;
            double bestScore = -1;
            int bestStartTime = -1;
            boolean anySchedulable = false;
            for (String id : remaining) {
                if (planner.isGrouped(id)) continue;
                Block block = blockMap.get(id);
                boolean allPredsScheduled = block.predecessorBlockIds().stream()
                        .filter(blockIds::contains)
                        .allMatch(pred -> engine.getScheduled().containsKey(pred));
                if (!allPredsScheduled) continue;
                anySchedulable = true;
                int minStart = engine.getEarliestPredecessorEnd(block);
                int startTime = engine.findEarliestValidStart(block, minStart);
                if (startTime < 0) continue;

                double score = (1.0 - weight) * cprNorm.getOrDefault(block.id(), 0.0)
                        + weight * computeLookBackOpa(block, currentAxisValue);
                if (score > bestScore
                        || (score == bestScore && (bestBlock == null || block.id().compareTo(bestBlock.id()) < 0))) {
                    bestScore = score;
                    bestBlock = block;
                    bestStartTime = startTime;
                }
            }

            // Best ready sequence group, scored by its highest-scoring member.
            String bestGroup = null;
            double bestGroupScore = -1;
            int bestGroupStart = -1;
            boolean anyGroupReady = false;
            for (String g : planner.groupIds()) {
                List<Block> members = planner.members(g);
                if (members.stream().noneMatch(m -> remaining.contains(m.id()))) continue;
                if (!planner.isReady(g, pred -> engine.getScheduled().containsKey(pred))) continue;
                anyGroupReady = true;
                int minStart = 0;
                for (Block m : members) {
                    minStart = Math.max(minStart, engine.getEarliestPredecessorEnd(m));
                }
                int startTime = engine.findEarliestContiguousGroupStart(members, minStart);
                if (startTime < 0) continue;
                double score = -1;
                for (Block m : members) {
                    double s = (1.0 - weight) * cprNorm.getOrDefault(m.id(), 0.0)
                            + weight * computeLookBackOpa(m, currentAxisValue);
                    if (s > score) score = s;
                }
                if (bestGroup == null || score > bestGroupScore
                        || (score == bestGroupScore && g.compareTo(bestGroup) < 0)) {
                    bestGroupScore = score;
                    bestGroup = g;
                    bestGroupStart = startTime;
                }
            }

            if (bestBlock == null && bestGroup == null) {
                String msg = (!anySchedulable && !anyGroupReady)
                        ? "No schedulable blocks found — possible missing predecessor outside block set "
                          + "or a sequence group blocked by a cyclic external dependency"
                        : "No feasible schedule found: remaining blocks cannot fit in any available window";
                return new ScheduleResult(false, msg, 0, List.of(),
                        System.currentTimeMillis() - startMs, null, null);
            }

            // Choose single vs group (tie → single, reproducing the baseline when no groups exist).
            boolean placeGroup = bestBlock == null
                    || (bestGroup != null && bestGroupScore > bestScore);

            if (!placeGroup) {
                engine.placeBlock(bestBlock, bestStartTime);
                updateAxisState(bestBlock, bestStartTime, currentAxisValue, currentAxisLatestStart);
                remaining.remove(bestBlock.id());
            } else {
                List<Block> members = planner.members(bestGroup);
                engine.placeGroupContiguous(members, bestGroupStart);
                int offset = 0;
                for (Block m : members) {
                    updateAxisState(m, bestGroupStart + offset, currentAxisValue, currentAxisLatestStart);
                    offset += m.durationHalfHours();
                }
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

    /**
     * Continuous OPA in [0, 1]: fraction of the block's declared axes whose
     * value equals the current machine value on that axis (or for which no
     * placed block has set the axis yet). Orientation-neutral blocks (no
     * axes declared) score 1.
     */
    /**
     * Record {@code block}'s axis values as the machine's current orientation,
     * per axis, iff this placement is the most recent start seen for that axis.
     */
    private static void updateAxisState(Block block, int startTime,
            Map<String, String> currentAxisValue, Map<String, Integer> currentAxisLatestStart) {
        for (var e : block.positionAxes().entrySet()) {
            String axis = e.getKey();
            Integer latest = currentAxisLatestStart.get(axis);
            if (latest == null || latest < startTime) {
                currentAxisLatestStart.put(axis, startTime);
                currentAxisValue.put(axis, e.getValue());
            }
        }
    }

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
