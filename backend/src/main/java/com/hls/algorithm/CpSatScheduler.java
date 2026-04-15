package com.hls.algorithm;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import com.google.ortools.util.Domain;
import com.hls.model.*;

import java.util.*;
import java.util.stream.Collectors;

public class CpSatScheduler implements Scheduler {

    static {
        Loader.loadNativeLibraries();
    }

    @Override
    public ScheduleResult schedule(List<Block> blocks, List<ShiftDay> shiftSchedule, Map<String, Object> params) {
        long startMs = System.currentTimeMillis();

        int timeLimitSeconds = ((Number) params.getOrDefault("cpSatTimeLimitSeconds", 60)).intValue();

        // Validate: check for cycles
        if (PrecedenceHelper.hasCycle(blocks)) {
            return new ScheduleResult(false, "Precedence cycle detected", 0,
                    List.of(), System.currentTimeMillis() - startMs, null, null);
        }

        int[] fteCapacity = TimelineHelper.buildFteCapacityArray(shiftSchedule);
        int horizonLength = shiftSchedule.size() * TimelineHelper.SLOTS_PER_DAY;

        Map<String, Block> blockMap = blocks.stream()
                .collect(Collectors.toMap(Block::id, b -> b));

        CpModel model = new CpModel();

        // Create variables
        Map<String, IntVar> startVars = new HashMap<>();
        Map<String, IntervalVar> intervalVars = new HashMap<>();

        for (Block block : blocks) {
            // Compute valid start-time domain
            List<Long> validStarts = new ArrayList<>();
            for (int t = 0; t <= horizonLength - block.durationHalfHours(); t++) {
                if (TimelineHelper.fitsInSingleDay(t, block.durationHalfHours())
                        && hasFteForBlock(t, block.durationHalfHours(), block.fteRequirement(), fteCapacity)) {
                    validStarts.add((long) t);
                }
            }

            if (validStarts.isEmpty()) {
                return new ScheduleResult(false,
                        "No feasible schedule found: block " + block.id() +
                                " (duration " + block.durationHalfHours() +
                                " half-hours) cannot fit in any single-day shift window",
                        0, List.of(), System.currentTimeMillis() - startMs, null, null);
            }

            long[] domain = validStarts.stream().mapToLong(Long::longValue).toArray();
            IntVar startVar = model.newIntVarFromDomain(Domain.fromValues(domain), "start_" + block.id());
            IntervalVar interval = model.newFixedSizeIntervalVar(startVar, block.durationHalfHours(), "interval_" + block.id());

            startVars.put(block.id(), startVar);
            intervalVars.put(block.id(), interval);
        }

        // Constraint 2: FTE capacity (cumulative with deficit intervals)
        int maxCapacity = Arrays.stream(fteCapacity).max().orElse(0);
        if (maxCapacity > 0) {
            CumulativeConstraint cumulative = model.addCumulative(maxCapacity);

            // Add block demands
            for (Block block : blocks) {
                cumulative.addDemand(intervalVars.get(block.id()), block.fteRequirement());
            }

            // Add deficit intervals for time-varying capacity
            int rangeStart = 0;
            int prevCap = fteCapacity.length > 0 ? fteCapacity[0] : 0;
            for (int t = 1; t <= horizonLength; t++) {
                int cap = (t < horizonLength) ? fteCapacity[t] : -1;
                if (cap != prevCap) {
                    if (prevCap < maxCapacity && t > rangeStart) {
                        int deficit = maxCapacity - prevCap;
                        IntVar dummyStart = model.newConstant(rangeStart);
                        IntervalVar deficitInterval = model.newFixedSizeIntervalVar(
                                dummyStart, t - rangeStart, "fteDeficit_" + rangeStart);
                        cumulative.addDemand(deficitInterval, deficit);
                    }
                    rangeStart = t;
                    prevCap = cap;
                }
            }
        }

        // Constraint 3: Spatial exclusivity (no-overlap per occupied zone — multi-zone aware)
        Map<String, List<IntervalVar>> zoneIntervals = new HashMap<>();
        for (Block block : blocks) {
            for (String zone : block.occupiedZones()) {
                zoneIntervals.computeIfAbsent(zone, k -> new ArrayList<>())
                        .add(intervalVars.get(block.id()));
            }
        }
        for (var entry : zoneIntervals.entrySet()) {
            if (entry.getValue().size() > 1) {
                model.addNoOverlap(entry.getValue());
            }
        }

        // Constraint 4: Tool exclusivity (no-overlap per exclusive tool)
        Map<String, List<IntervalVar>> toolIntervals = new HashMap<>();
        for (Block block : blocks) {
            ToolRequirement tool = block.requiredTool();
            if (tool != null && tool.exclusive()) {
                toolIntervals.computeIfAbsent(tool.toolName(), k -> new ArrayList<>())
                        .add(intervalVars.get(block.id()));
            }
        }
        for (var entry : toolIntervals.entrySet()) {
            if (entry.getValue().size() > 1) {
                model.addNoOverlap(entry.getValue());
            }
        }

        // Constraint 5: Precedence
        for (Block block : blocks) {
            for (String predId : block.predecessorBlockIds()) {
                if (startVars.containsKey(predId)) {
                    Block pred = blockMap.get(predId);
                    // start_block >= start_pred + duration_pred
                    model.addGreaterOrEqual(
                            startVars.get(block.id()),
                            LinearExpr.affine(startVars.get(predId), 1, pred.durationHalfHours())
                    );
                }
            }
        }

        // Constraint 6: Operational position (per-axis thesis rule).
        // For each axis independently, blocks with different non-empty values
        // on that axis cannot overlap. Blocks silent on the axis are unconstrained.
        Map<String, Map<String, List<String>>> axisGroups = new HashMap<>();
        for (Block block : blocks) {
            for (var entry : block.positionAxes().entrySet()) {
                axisGroups
                        .computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                        .computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                        .add(block.id());
            }
        }
        for (var axisEntry : axisGroups.entrySet()) {
            Map<String, List<String>> valueToBlocks = axisEntry.getValue();
            List<String> values = new ArrayList<>(valueToBlocks.keySet());
            for (int a = 0; a < values.size(); a++) {
                for (int b = a + 1; b < values.size(); b++) {
                    for (String idA : valueToBlocks.get(values.get(a))) {
                        for (String idB : valueToBlocks.get(values.get(b))) {
                            model.addNoOverlap(List.of(intervalVars.get(idA), intervalVars.get(idB)));
                        }
                    }
                }
            }
        }

        // Objective: minimize makespan (calendar day of latest completion)
        IntVar makespan = model.newIntVar(1, shiftSchedule.size(), "makespan");
        for (Block block : blocks) {
            // makespan * 48 >= start_i + duration_i
            model.addGreaterOrEqual(
                    LinearExpr.term(makespan, TimelineHelper.SLOTS_PER_DAY),
                    LinearExpr.affine(startVars.get(block.id()), 1, block.durationHalfHours())
            );
        }
        model.minimize(makespan);

        // Solve
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
        CpSolverStatus status = solver.solve(model);

        long runtimeMs = System.currentTimeMillis() - startMs;

        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            List<ScheduledBlock> result = new ArrayList<>();
            for (Block block : blocks) {
                int start = (int) solver.value(startVars.get(block.id()));
                int end = start + block.durationHalfHours();
                int dayIndex = TimelineHelper.dayIndex(start);
                result.add(new ScheduledBlock(block.id(), start, end, dayIndex));
            }

            int makespanValue = (int) solver.value(makespan);
            double bestBound = solver.bestObjectiveBound();
            Double gap = (status == CpSolverStatus.OPTIMAL) ? 0.0
                    : (makespanValue > 0 ? (makespanValue - bestBound) / makespanValue : null);

            return new ScheduleResult(true, null, makespanValue, result, runtimeMs,
                    (int) Math.round(bestBound), gap);
        }

        return new ScheduleResult(false,
                "No feasible schedule found by CP-SAT solver within time limit",
                0, List.of(), runtimeMs, null, null);
    }

    private boolean hasFteForBlock(int startTime, int duration, int fteRequired, int[] fteCapacity) {
        for (int t = startTime; t < startTime + duration; t++) {
            if (t >= fteCapacity.length || fteCapacity[t] < fteRequired) {
                return false;
            }
        }
        return true;
    }
}
