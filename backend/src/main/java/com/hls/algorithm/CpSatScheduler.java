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
            OdmConstraints odm = block.odm();
            // Compute valid start-time domain, intersected with any ODM calendar/hour pin
            // (constraint 8). A pin simply removes the disallowed start times from the domain.
            List<Long> validStarts = new ArrayList<>();
            for (int t = 0; t <= horizonLength - block.durationHalfHours(); t++) {
                if (odm.hasDayPin() && TimelineHelper.dayIndex(t) != odm.pinnedDay()) continue;
                if (odm.hasHourPin()
                        && (t % TimelineHelper.SLOTS_PER_DAY) != (odm.pinnedStartHour() - 1) * 2) continue;
                if (TimelineHelper.fitsInSingleDay(t, block.durationHalfHours())
                        && hasFteForBlock(t, block.durationHalfHours(), block.fteRequirement(), fteCapacity)) {
                    validStarts.add((long) t);
                }
            }

            if (validStarts.isEmpty()) {
                String pin = odm.describePin();
                String where = pin == null
                        ? " in any single-day shift window"
                        : " under its ODM pin (" + pin + ")";
                return new ScheduleResult(false,
                        "No feasible schedule found: block " + block.id() +
                                " (duration " + block.durationHalfHours() +
                                " half-hours) cannot fit" + where,
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

        // Constraint 7: Sequence-group contiguity. Each group's members are
        // serialised (no-overlap) and confined to an envelope interval of length
        // = total member duration. Disjoint + within + equal-total-length forces
        // the members to tile the envelope with no gaps (one contiguous run).
        // Other blocks may overlap the envelope, so no envelope-vs-others rule.
        Map<String, List<Block>> sequenceGroups = new LinkedHashMap<>();
        for (Block block : blocks) {
            String group = block.odm().sequenceGroup();
            if (group != null) {
                sequenceGroups.computeIfAbsent(group, k -> new ArrayList<>()).add(block);
            }
        }
        for (var entry : sequenceGroups.entrySet()) {
            List<Block> members = entry.getValue();
            if (members.size() < 2) continue;
            int total = members.stream().mapToInt(Block::durationHalfHours).sum();
            if (total > horizonLength) {
                return new ScheduleResult(false,
                        "No feasible schedule found: sequence group '" + entry.getKey() +
                                "' has total duration " + total + " half-hours, exceeding the " +
                                horizonLength + "-slot horizon",
                        0, List.of(), System.currentTimeMillis() - startMs, null, null);
            }
            List<IntervalVar> memberIntervals = new ArrayList<>(members.size());
            for (Block m : members) {
                memberIntervals.add(intervalVars.get(m.id()));
            }
            model.addNoOverlap(memberIntervals);

            IntVar envStart = model.newIntVar(0, horizonLength - total, "sgEnvelope_" + entry.getKey());
            for (Block m : members) {
                // envStart <= start_m  and  start_m + dur_m <= envStart + total
                model.addGreaterOrEqual(startVars.get(m.id()), envStart);
                model.addLessOrEqual(
                        LinearExpr.affine(startVars.get(m.id()), 1, m.durationHalfHours()),
                        LinearExpr.affine(envStart, 1, total));
            }
        }

        // Constraint 9: Parallelism exclusivity. A Parallelism = No block may not
        // overlap ANY other block. Encode as pairwise no-overlaps; for two
        // no-parallel blocks the pair is added once (driven by the lower id).
        for (Block n : blocks) {
            if (!n.odm().noParallel()) continue;
            for (Block other : blocks) {
                if (other.id().equals(n.id())) continue;
                if (other.odm().noParallel() && other.id().compareTo(n.id()) < 0) continue;
                model.addNoOverlap(List.of(intervalVars.get(n.id()), intervalVars.get(other.id())));
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

        // Optional warm-start hints: seed the solver with a feasible solution
        // (typically from Candidate C). Hints guide search; they do not
        // constrain, so an infeasible or stale hint is silently ignored.
        @SuppressWarnings("unchecked")
        Map<String, Integer> warmStartHints = (Map<String, Integer>) params.get("warmStartHints");
        if (warmStartHints != null && !warmStartHints.isEmpty()) {
            for (Block block : blocks) {
                Integer hintStart = warmStartHints.get(block.id());
                if (hintStart != null) {
                    model.addHint(startVars.get(block.id()), hintStart);
                }
            }
        }

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
